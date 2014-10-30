/**
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.aurora.scheduler.async;

import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

import com.google.common.base.Function;
import com.google.common.base.Optional;
import com.google.common.base.Supplier;
import com.google.common.base.Suppliers;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSet;
import com.google.common.util.concurrent.RateLimiter;
import com.twitter.common.quantity.Amount;
import com.twitter.common.quantity.Time;
import com.twitter.common.stats.Stat;
import com.twitter.common.stats.StatsProvider;
import com.twitter.common.testing.easymock.EasyMockTest;
import com.twitter.common.util.BackoffStrategy;
import com.twitter.common.util.testing.FakeClock;

import org.apache.aurora.gen.AssignedTask;
import org.apache.aurora.gen.HostStatus;
import org.apache.aurora.gen.Identity;
import org.apache.aurora.gen.JobKey;
import org.apache.aurora.gen.MaintenanceMode;
import org.apache.aurora.gen.ScheduleStatus;
import org.apache.aurora.gen.ScheduledTask;
import org.apache.aurora.gen.TaskConfig;
import org.apache.aurora.scheduler.Driver;
import org.apache.aurora.scheduler.async.OfferQueue.OfferQueueImpl;
import org.apache.aurora.scheduler.async.OfferQueue.OfferReturnDelay;
import org.apache.aurora.scheduler.async.TaskScheduler.TaskSchedulerImpl;
import org.apache.aurora.scheduler.base.Query;
import org.apache.aurora.scheduler.base.Tasks;
import org.apache.aurora.scheduler.events.PubsubEvent.HostMaintenanceStateChange;
import org.apache.aurora.scheduler.events.PubsubEvent.TaskStateChange;
import org.apache.aurora.scheduler.events.PubsubEvent.TasksDeleted;
import org.apache.aurora.scheduler.filter.AttributeAggregate;
import org.apache.aurora.scheduler.state.MaintenanceController;
import org.apache.aurora.scheduler.state.StateManager;
import org.apache.aurora.scheduler.state.TaskAssigner;
import org.apache.aurora.scheduler.storage.AttributeStore;
import org.apache.aurora.scheduler.storage.Storage;
import org.apache.aurora.scheduler.storage.Storage.MutableStoreProvider;
import org.apache.aurora.scheduler.storage.Storage.MutateWork;
import org.apache.aurora.scheduler.storage.Storage.StorageException;
import org.apache.aurora.scheduler.storage.TaskStore;
import org.apache.aurora.scheduler.storage.entities.IScheduledTask;
import org.apache.aurora.scheduler.storage.mem.MemStorage;
import org.apache.mesos.Protos.Offer;
import org.apache.mesos.Protos.OfferID;
import org.apache.mesos.Protos.SlaveID;
import org.apache.mesos.Protos.TaskID;
import org.apache.mesos.Protos.TaskInfo;
import org.easymock.Capture;
import org.easymock.EasyMock;
import org.junit.Before;
import org.junit.Test;

import static org.apache.aurora.gen.ScheduleStatus.FINISHED;
import static org.apache.aurora.gen.ScheduleStatus.INIT;
import static org.apache.aurora.gen.ScheduleStatus.KILLED;
import static org.apache.aurora.gen.ScheduleStatus.LOST;
import static org.apache.aurora.gen.ScheduleStatus.PENDING;
import static org.apache.aurora.gen.ScheduleStatus.RUNNING;
import static org.apache.aurora.scheduler.async.OfferQueue.HostOffer;
import static org.easymock.EasyMock.capture;
import static org.easymock.EasyMock.eq;
import static org.easymock.EasyMock.expect;
import static org.easymock.EasyMock.expectLastCall;
import static org.easymock.EasyMock.isA;
import static org.junit.Assert.assertEquals;

/**
 * TODO(wfarner): Break this test up to independently test TaskSchedulerImpl and OfferQueueImpl.
 */
public class TaskSchedulerTest extends EasyMockTest {

  private static final long FIRST_SCHEDULE_DELAY_MS = 1L;

  private static final HostOffer OFFER_A =
      new HostOffer(Offers.makeOffer("OFFER_A", "HOST_A"), MaintenanceMode.NONE);
  private static final HostOffer OFFER_B =
      new HostOffer(Offers.makeOffer("OFFER_B", "HOST_B"), MaintenanceMode.SCHEDULED);
  private static final HostOffer OFFER_C =
      new HostOffer(Offers.makeOffer("OFFER_C", "HOST_C"), MaintenanceMode.DRAINING);
  private static final HostOffer OFFER_D =
      new HostOffer(Offers.makeOffer("OFFER_D", "HOST_D"), MaintenanceMode.DRAINED);

  private Storage storage;

  private MaintenanceController maintenance;
  private StateManager stateManager;
  private TaskAssigner assigner;
  private BackoffStrategy retryStrategy;
  private Driver driver;
  private ScheduledExecutorService executor;
  private ScheduledFuture<?> future;
  private OfferReturnDelay returnDelay;
  private OfferQueue offerQueue;
  private TaskGroups taskGroups;
  private FakeClock clock;
  private StatsProvider statsProvider;
  private RescheduleCalculator rescheduleCalculator;
  private Preemptor preemptor;
  private AttributeAggregate emptyJob;
  private Amount<Long, Time> reservationDuration = Amount.of(1L, Time.MINUTES);

  @Before
  public void setUp() {
    storage = MemStorage.newEmptyStorage();
    maintenance = createMock(MaintenanceController.class);
    stateManager = createMock(StateManager.class);
    assigner = createMock(TaskAssigner.class);
    retryStrategy = createMock(BackoffStrategy.class);
    driver = createMock(Driver.class);
    executor = createMock(ScheduledExecutorService.class);
    future = createMock(ScheduledFuture.class);
    returnDelay = createMock(OfferReturnDelay.class);
    clock = new FakeClock();
    clock.setNowMillis(0);
    statsProvider = createMock(StatsProvider.class);
    rescheduleCalculator = createMock(RescheduleCalculator.class);
    preemptor = createMock(Preemptor.class);
    emptyJob = new AttributeAggregate(
        Suppliers.ofInstance(ImmutableSet.<IScheduledTask>of()),
        createMock(AttributeStore.class));
  }

  private void replayAndCreateScheduler() {
    Capture<Supplier<Long>> cacheSizeSupplier = createCapture();
    Stat<Long> stat = createMock(new Clazz<Stat<Long>>() { });
    expect(statsProvider.makeGauge(
        EasyMock.eq(TaskSchedulerImpl.RESERVATIONS_CACHE_SIZE_STAT),
        capture(cacheSizeSupplier))).andReturn(stat);

    control.replay();
    offerQueue = new OfferQueueImpl(driver, returnDelay, executor, maintenance);
    TaskScheduler scheduler = new TaskSchedulerImpl(storage,
        stateManager,
        assigner,
        offerQueue,
        preemptor,
        reservationDuration,
        clock,
        statsProvider);
    taskGroups = new TaskGroups(
        executor,
        Amount.of(FIRST_SCHEDULE_DELAY_MS, Time.MILLISECONDS),
        retryStrategy,
        RateLimiter.create(100),
        scheduler,
        rescheduleCalculator);
    assertEquals(0L, (long) cacheSizeSupplier.getValue().get());
  }

  private Capture<Runnable> expectOffer() {
    return expectOfferDeclineIn(10);
  }

  private Capture<Runnable> expectOfferDeclineIn(int delayMillis) {
    expect(returnDelay.get()).andReturn(Amount.of(delayMillis, Time.MILLISECONDS));
    Capture<Runnable> runnable = createCapture();
    executor.schedule(capture(runnable), eq((long) delayMillis), eq(TimeUnit.MILLISECONDS));
    expectLastCall().andReturn(createMock(ScheduledFuture.class));
    return runnable;
  }

  private void changeState(
      IScheduledTask task,
      ScheduleStatus oldState,
      ScheduleStatus newState) {

    final IScheduledTask copy = IScheduledTask.build(task.newBuilder().setStatus(newState));
    // Insert the task if it doesn't already exist.
    storage.write(new MutateWork.NoResult.Quiet() {
      @Override
      protected void execute(MutableStoreProvider storeProvider) {
        TaskStore.Mutable taskStore = storeProvider.getUnsafeTaskStore();
        if (taskStore.fetchTasks(Query.taskScoped(Tasks.id(copy))).isEmpty()) {
          taskStore.saveTasks(ImmutableSet.of(copy));
        }
      }
    });
    taskGroups.taskChangedState(TaskStateChange.transition(copy, oldState));
  }

  private Capture<Runnable> expectTaskRetryIn(long penaltyMs) {
    Capture<Runnable> capture = createCapture();
    executor.schedule(
        capture(capture),
        eq(penaltyMs),
        eq(TimeUnit.MILLISECONDS));
    expectLastCall().andReturn(future);
    return capture;
  }

  private Capture<Runnable> expectTaskGroupBackoff(long previousPenaltyMs, long nextPenaltyMs) {
    expect(retryStrategy.calculateBackoffMs(previousPenaltyMs)).andReturn(nextPenaltyMs);
    return expectTaskRetryIn(nextPenaltyMs);
  }

  @Test
  public void testNoTasks() {
    expectAnyMaintenanceCalls();
    expectOfferDeclineIn(10);
    expectOfferDeclineIn(10);

    replayAndCreateScheduler();

    offerQueue.addOffer(OFFER_A.getOffer());
    offerQueue.addOffer(OFFER_B.getOffer());
  }

  @Test
  public void testNoOffers() {
    Capture<Runnable> timeoutCapture = expectTaskRetryIn(FIRST_SCHEDULE_DELAY_MS);
    expectTaskGroupBackoff(FIRST_SCHEDULE_DELAY_MS, 10);
    expect(preemptor.findPreemptionSlotFor("a", emptyJob)).andReturn(Optional.<String>absent());

    replayAndCreateScheduler();

    changeState(makeTask("a"), INIT, PENDING);
    timeoutCapture.getValue().run();
  }

  private IScheduledTask makeTask(String taskId) {
    return IScheduledTask.build(new ScheduledTask()
        .setAssignedTask(new AssignedTask()
            .setInstanceId(0)
            .setTaskId(taskId)
            .setTask(new TaskConfig()
                .setJob(new JobKey("role-" + taskId, "env-" + taskId, "job-" + taskId))
                .setJobName("job-" + taskId)
                .setOwner(new Identity().setRole("role-" + taskId).setUser("user-" + taskId))
                .setEnvironment("env-" + taskId))));
  }

  private IScheduledTask makeTask(String taskId, ScheduleStatus status) {
    return IScheduledTask.build(makeTask(taskId).newBuilder().setStatus(status));
  }

  @Test
  public void testLoadFromStorage() {
    final IScheduledTask a = makeTask("a", KILLED);
    final IScheduledTask b = makeTask("b", PENDING);
    final IScheduledTask c = makeTask("c", RUNNING);

    expect(rescheduleCalculator.getStartupScheduleDelayMs(b)).andReturn(10L);
    expectTaskRetryIn(10);

    replayAndCreateScheduler();

    storage.write(new MutateWork.NoResult.Quiet() {
      @Override
      protected void execute(MutableStoreProvider store) {
        store.getUnsafeTaskStore().saveTasks(ImmutableSet.of(a, b, c));
      }
    });
    for (IScheduledTask task : ImmutableList.of(a, b, c)) {
      taskGroups.taskChangedState(TaskStateChange.initialized(task));
    }
    changeState(c, RUNNING, FINISHED);
  }

  @Test
  public void testTaskMissing() {
    Capture<Runnable> timeoutCapture = expectTaskRetryIn(FIRST_SCHEDULE_DELAY_MS);

    replayAndCreateScheduler();

    taskGroups.taskChangedState(TaskStateChange.transition(makeTask("a", PENDING), INIT));
    timeoutCapture.getValue().run();
  }

  @Test
  public void testTaskAssigned() {
    expectAnyMaintenanceCalls();
    expectOfferDeclineIn(10);

    IScheduledTask task = makeTask("a", PENDING);
    TaskInfo mesosTask = makeTaskInfo(task);

    Capture<Runnable> timeoutCapture = expectTaskRetryIn(FIRST_SCHEDULE_DELAY_MS);
    expect(assigner.maybeAssign(OFFER_A, task, emptyJob)).andReturn(Optional.<TaskInfo>absent());
    expect(preemptor.findPreemptionSlotFor("a", emptyJob)).andReturn(Optional.<String>absent());

    Capture<Runnable> timeoutCapture2 = expectTaskGroupBackoff(FIRST_SCHEDULE_DELAY_MS, 10);
    expect(assigner.maybeAssign(OFFER_A, task, emptyJob)).andReturn(Optional.of(mesosTask));
    driver.launchTask(OFFER_A.getOffer().getId(), mesosTask);

    Capture<Runnable> timeoutCapture3 = expectTaskRetryIn(FIRST_SCHEDULE_DELAY_MS);
    expectTaskGroupBackoff(FIRST_SCHEDULE_DELAY_MS, 10);
    expect(preemptor.findPreemptionSlotFor("b", emptyJob)).andReturn(Optional.<String>absent());

    replayAndCreateScheduler();

    offerQueue.addOffer(OFFER_A.getOffer());
    changeState(task, INIT, PENDING);
    timeoutCapture.getValue().run();
    timeoutCapture2.getValue().run();

    // Ensure the offer was consumed.
    changeState(makeTask("b"), INIT, PENDING);
    timeoutCapture3.getValue().run();
  }

  @Test
  public void testDriverNotReady() {
    IScheduledTask task = makeTask("a", PENDING);
    TaskInfo mesosTask = TaskInfo.newBuilder()
        .setName(Tasks.id(task))
        .setTaskId(TaskID.newBuilder().setValue(Tasks.id(task)))
        .setSlaveId(SlaveID.newBuilder().setValue("slaveId"))
        .build();

    Capture<Runnable> timeoutCapture = expectTaskRetryIn(FIRST_SCHEDULE_DELAY_MS);
    expectAnyMaintenanceCalls();
    expectOfferDeclineIn(10);
    expect(assigner.maybeAssign(OFFER_A, task, emptyJob)).andReturn(Optional.of(mesosTask));
    driver.launchTask(OFFER_A.getOffer().getId(), mesosTask);
    expectLastCall().andThrow(new IllegalStateException("Driver not ready."));
    expect(stateManager.changeState(
        "a",
        Optional.of(PENDING),
        LOST,
        TaskSchedulerImpl.LAUNCH_FAILED_MSG))
        .andReturn(true);

    replayAndCreateScheduler();

    changeState(task, INIT, PENDING);
    offerQueue.addOffer(OFFER_A.getOffer());
    timeoutCapture.getValue().run();
  }

  @Test
  public void testStorageException() {
    IScheduledTask task = makeTask("a", PENDING);
    TaskInfo mesosTask = TaskInfo.newBuilder()
        .setName(Tasks.id(task))
        .setTaskId(TaskID.newBuilder().setValue(Tasks.id(task)))
        .setSlaveId(SlaveID.newBuilder().setValue("slaveId"))
        .build();

    Capture<Runnable> timeoutCapture = expectTaskRetryIn(FIRST_SCHEDULE_DELAY_MS);
    expectAnyMaintenanceCalls();
    expectOfferDeclineIn(10);
    expect(assigner.maybeAssign(OFFER_A, task, emptyJob))
        .andThrow(new StorageException("Injected failure."));

    Capture<Runnable> timeoutCapture2 = expectTaskGroupBackoff(FIRST_SCHEDULE_DELAY_MS, 10);
    expect(assigner.maybeAssign(OFFER_A, task, emptyJob)).andReturn(Optional.of(mesosTask));
    driver.launchTask(OFFER_A.getOffer().getId(), mesosTask);
    expectLastCall();

    replayAndCreateScheduler();

    changeState(task, INIT, PENDING);
    offerQueue.addOffer(OFFER_A.getOffer());
    timeoutCapture.getValue().run();
    timeoutCapture2.getValue().run();
  }

  @Test
  public void testExpiration() {
    IScheduledTask task = makeTask("a", PENDING);

    Capture<Runnable> timeoutCapture = expectTaskRetryIn(FIRST_SCHEDULE_DELAY_MS);
    Capture<Runnable> offerExpirationCapture = expectOfferDeclineIn(10);
    expectAnyMaintenanceCalls();
    expect(assigner.maybeAssign(OFFER_A, task, emptyJob)).andReturn(Optional.<TaskInfo>absent());
    Capture<Runnable> timeoutCapture2 = expectTaskGroupBackoff(FIRST_SCHEDULE_DELAY_MS, 10);
    expect(preemptor.findPreemptionSlotFor("a", emptyJob)).andReturn(Optional.<String>absent());
    driver.declineOffer(OFFER_A.getOffer().getId());
    expectTaskGroupBackoff(10, 20);
    expect(preemptor.findPreemptionSlotFor("a", emptyJob)).andReturn(Optional.<String>absent());

    replayAndCreateScheduler();

    changeState(task, INIT, PENDING);
    offerQueue.addOffer(OFFER_A.getOffer());
    timeoutCapture.getValue().run();
    offerExpirationCapture.getValue().run();
    timeoutCapture2.getValue().run();
  }

  @Test
  public void testOneOfferPerSlave() {
    expectAnyMaintenanceCalls();
    Capture<Runnable> offerExpirationCapture = expectOfferDeclineIn(10);

    Offer offerAB =
        Offers.makeOffer("OFFER_B").toBuilder().setSlaveId(OFFER_A.getOffer().getSlaveId()).build();

    driver.declineOffer(OFFER_A.getOffer().getId());
    driver.declineOffer(offerAB.getId());

    replayAndCreateScheduler();

    offerQueue.addOffer(OFFER_A.getOffer());
    offerQueue.addOffer(offerAB);
    offerExpirationCapture.getValue().run();
  }

  @Test
  public void testDontDeclineAcceptedOffer() throws OfferQueue.LaunchException {
    expectAnyMaintenanceCalls();
    Capture<Runnable> offerExpirationCapture = expectOfferDeclineIn(10);

    Function<HostOffer, Optional<TaskInfo>> offerAcceptor =
        createMock(new Clazz<Function<HostOffer, Optional<TaskInfo>>>() { });
    final TaskInfo taskInfo = TaskInfo.getDefaultInstance();
    expect(offerAcceptor.apply(OFFER_A)).andReturn(Optional.of(taskInfo));
    driver.launchTask(OFFER_A.getOffer().getId(), taskInfo);

    replayAndCreateScheduler();

    offerQueue.addOffer(OFFER_A.getOffer());
    offerQueue.launchFirst(offerAcceptor);
    offerExpirationCapture.getValue().run();
  }

  @Test
  public void testBasicMaintenancePreferences() {
    expectOffer();
    expect(maintenance.getMode("HOST_D")).andReturn(OFFER_D.getMode());
    expectOffer();
    expect(maintenance.getMode("HOST_C")).andReturn(OFFER_C.getMode());
    expectOffer();
    expect(maintenance.getMode("HOST_B")).andReturn(OFFER_B.getMode());
    expectOffer();
    expect(maintenance.getMode("HOST_A")).andReturn(OFFER_A.getMode());

    IScheduledTask taskA = makeTask("A", PENDING);
    TaskInfo mesosTaskA = makeTaskInfo(taskA);
    expect(assigner.maybeAssign(OFFER_A, taskA, emptyJob)).andReturn(Optional.of(mesosTaskA));
    driver.launchTask(OFFER_A.getOffer().getId(), mesosTaskA);
    Capture<Runnable> captureA = expectTaskRetryIn(FIRST_SCHEDULE_DELAY_MS);

    IScheduledTask taskB = makeTask("B", PENDING);
    TaskInfo mesosTaskB = makeTaskInfo(taskB);
    expect(assigner.maybeAssign(OFFER_B, taskB, emptyJob)).andReturn(Optional.of(mesosTaskB));
    driver.launchTask(OFFER_B.getOffer().getId(), mesosTaskB);
    Capture<Runnable> captureB = expectTaskRetryIn(FIRST_SCHEDULE_DELAY_MS);

    replayAndCreateScheduler();

    offerQueue.addOffer(OFFER_D.getOffer());
    offerQueue.addOffer(OFFER_C.getOffer());
    offerQueue.addOffer(OFFER_B.getOffer());
    offerQueue.addOffer(OFFER_A.getOffer());

    changeState(taskA, INIT, PENDING);
    captureA.getValue().run();

    changeState(taskB, INIT, PENDING);
    captureB.getValue().run();
  }

  @Test
  public void testChangingMaintenancePreferences() {
    expectOffer();
    expect(maintenance.getMode("HOST_A")).andReturn(OFFER_A.getMode());
    expectOffer();
    expect(maintenance.getMode("HOST_B")).andReturn(OFFER_B.getMode());
    expectOffer();
    expect(maintenance.getMode("HOST_C")).andReturn(OFFER_C.getMode());

    IScheduledTask taskA = makeTask("A", PENDING);
    TaskInfo mesosTaskA = makeTaskInfo(taskA);
    expect(assigner.maybeAssign(OFFER_B, taskA, emptyJob)).andReturn(Optional.of(mesosTaskA));
    driver.launchTask(OFFER_B.getOffer().getId(), mesosTaskA);
    Capture<Runnable> captureA = expectTaskRetryIn(FIRST_SCHEDULE_DELAY_MS);

    IScheduledTask taskB = makeTask("B", PENDING);
    TaskInfo mesosTaskB = makeTaskInfo(taskB);
    HostOffer updatedOfferC =
        new HostOffer(OFFER_C.getOffer(), MaintenanceMode.NONE);
    expect(assigner.maybeAssign(updatedOfferC, taskB, emptyJob)).andReturn(Optional.of(mesosTaskB));
    driver.launchTask(OFFER_C.getOffer().getId(), mesosTaskB);
    Capture<Runnable> captureB = expectTaskRetryIn(FIRST_SCHEDULE_DELAY_MS);

    replayAndCreateScheduler();

    offerQueue.addOffer(OFFER_A.getOffer());
    offerQueue.addOffer(OFFER_B.getOffer());
    offerQueue.addOffer(OFFER_C.getOffer());

    // Initially, we'd expect the offers to be consumed in order (A, B), with (C) unschedulable

    // Expected order now (B), with (C, A) unschedulable
    changeHostMaintenanceState("HOST_A", MaintenanceMode.DRAINING);
    changeState(taskA, INIT, PENDING);
    captureA.getValue().run();

    // Expected order now (C), with (A) unschedulable and (B) already consumed
    changeHostMaintenanceState("HOST_C", MaintenanceMode.NONE);
    changeState(taskB, INIT, PENDING);
    captureB.getValue().run();
  }

  private Capture<IScheduledTask> expectTaskScheduled(IScheduledTask task) {
    TaskInfo mesosTask = makeTaskInfo(task);
    Capture<IScheduledTask> taskScheduled = createCapture();
    expect(assigner.maybeAssign(
        EasyMock.<HostOffer>anyObject(),
        capture(taskScheduled),
        EasyMock.eq(emptyJob)))
        .andReturn(Optional.of(mesosTask));
    driver.launchTask(EasyMock.<OfferID>anyObject(), eq(mesosTask));
    return taskScheduled;
  }

  @Test
  public void testResistsStarvation() {
    // TODO(wfarner): This test requires intimate knowledge of the way futures are used inside
    // TaskScheduler.  It's time to test using a real ScheduledExecutorService.

    expectAnyMaintenanceCalls();

    IScheduledTask jobA0 = makeTask("a0", PENDING);

    ScheduledTask jobA1Builder = jobA0.newBuilder();
    jobA1Builder.getAssignedTask().setTaskId("a1");
    jobA1Builder.getAssignedTask().setInstanceId(1);
    IScheduledTask jobA1 = IScheduledTask.build(jobA1Builder);

    ScheduledTask jobA2Builder = jobA0.newBuilder();
    jobA2Builder.getAssignedTask().setTaskId("a2");
    jobA2Builder.getAssignedTask().setInstanceId(2);
    IScheduledTask jobA2 = IScheduledTask.build(jobA2Builder);

    IScheduledTask jobB0 = makeTask("b0", PENDING);

    expectOfferDeclineIn(10);
    expectOfferDeclineIn(10);
    expectOfferDeclineIn(10);
    expectOfferDeclineIn(10);

    Capture<Runnable> timeoutA = expectTaskRetryIn(FIRST_SCHEDULE_DELAY_MS);
    Capture<Runnable> timeoutB = expectTaskRetryIn(FIRST_SCHEDULE_DELAY_MS);

    Capture<IScheduledTask> firstScheduled = expectTaskScheduled(jobA0);
    Capture<IScheduledTask> secondScheduled = expectTaskScheduled(jobB0);

    // Expect another watch of the task group for job A.
    expectTaskRetryIn(FIRST_SCHEDULE_DELAY_MS);

    replayAndCreateScheduler();

    offerQueue.addOffer(OFFER_A.getOffer());
    offerQueue.addOffer(OFFER_B.getOffer());
    offerQueue.addOffer(OFFER_C.getOffer());
    offerQueue.addOffer(OFFER_D.getOffer());
    changeState(jobA0, INIT, PENDING);
    changeState(jobA1, INIT, PENDING);
    changeState(jobA2, INIT, PENDING);
    changeState(jobB0, INIT, PENDING);
    timeoutA.getValue().run();
    timeoutB.getValue().run();
    assertEquals(
        ImmutableSet.of(jobA0, jobB0),
        ImmutableSet.of(firstScheduled.getValue(), secondScheduled.getValue()));
  }

  @Test
  public void testTaskDeleted() {
    expectAnyMaintenanceCalls();
    expectOfferDeclineIn(10);

    final IScheduledTask task = makeTask("a", PENDING);

    Capture<Runnable> timeoutCapture = expectTaskRetryIn(FIRST_SCHEDULE_DELAY_MS);
    expect(assigner.maybeAssign(OFFER_A, task, emptyJob)).andReturn(Optional.<TaskInfo>absent());
    expectTaskGroupBackoff(FIRST_SCHEDULE_DELAY_MS, 20);
    expect(preemptor.findPreemptionSlotFor("a", emptyJob)).andReturn(Optional.<String>absent());

    replayAndCreateScheduler();

    offerQueue.addOffer(OFFER_A.getOffer());
    changeState(task, INIT, PENDING);
    timeoutCapture.getValue().run();

    // Ensure the offer was consumed.
    changeState(task, INIT, PENDING);
    storage.write(new MutateWork.NoResult.Quiet() {
      @Override
      protected void execute(MutableStoreProvider storeProvider) {
        storeProvider.getUnsafeTaskStore().deleteTasks(Tasks.ids(task));
      }
    });
    taskGroups.tasksDeleted(new TasksDeleted(ImmutableSet.of(task)));
    timeoutCapture.getValue().run();
  }

  private TaskInfo makeTaskInfo(IScheduledTask task) {
    return TaskInfo.newBuilder()
        .setName(Tasks.id(task))
        .setTaskId(TaskID.newBuilder().setValue(Tasks.id(task)))
        .setSlaveId(SlaveID.newBuilder().setValue("slave-id" + task.toString()))
        .build();
  }

  private void expectAnyMaintenanceCalls() {
    expect(maintenance.getMode(isA(String.class))).andReturn(MaintenanceMode.NONE).anyTimes();
  }

  private void changeHostMaintenanceState(String hostName, MaintenanceMode mode) {
    offerQueue.hostChangedState(new HostMaintenanceStateChange(new HostStatus(hostName, mode)));
  }
}
