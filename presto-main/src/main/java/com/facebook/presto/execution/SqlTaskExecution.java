/*
 * Copyright 2004-present Facebook. All Rights Reserved.
 */
package com.facebook.presto.execution;

import com.facebook.presto.OutputBuffers;
import com.facebook.presto.ScheduledSplit;
import com.facebook.presto.TaskSource;
import com.facebook.presto.client.FailureInfo;
import com.facebook.presto.event.query.QueryMonitor;
import com.facebook.presto.execution.StateMachine.StateChangeListener;
import com.facebook.presto.execution.TaskExecutor.TaskHandle;
import com.facebook.presto.operator.Driver;
import com.facebook.presto.operator.DriverContext;
import com.facebook.presto.operator.DriverFactory;
import com.facebook.presto.operator.PipelineContext;
import com.facebook.presto.operator.TaskContext;
import com.facebook.presto.operator.TaskOutputOperator.TaskOutputFactory;
import com.facebook.presto.spi.Split;
import com.facebook.presto.sql.analyzer.Session;
import com.facebook.presto.sql.planner.LocalExecutionPlanner;
import com.facebook.presto.sql.planner.LocalExecutionPlanner.LocalExecutionPlan;
import com.facebook.presto.sql.planner.PlanFragment;
import com.facebook.presto.sql.planner.plan.PlanNodeId;
import com.facebook.presto.util.SetThreadName;
import com.google.common.base.Function;
import com.google.common.base.Functions;
import com.google.common.base.Objects;
import com.google.common.base.Preconditions;
import com.google.common.collect.HashMultimap;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.SetMultimap;
import com.google.common.util.concurrent.FutureCallback;
import com.google.common.util.concurrent.Futures;
import com.google.common.util.concurrent.ListenableFuture;
import io.airlift.units.DataSize;
import io.airlift.units.Duration;
import org.joda.time.DateTime;

import javax.annotation.concurrent.GuardedBy;

import java.lang.ref.WeakReference;
import java.net.URI;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map.Entry;
import java.util.Set;
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

import static com.facebook.presto.util.Failures.toFailures;
import static com.google.common.base.Preconditions.checkArgument;
import static com.google.common.base.Preconditions.checkNotNull;
import static java.lang.Math.max;

public class SqlTaskExecution
        implements TaskExecution
{
    private final TaskId taskId;
    private final URI location;
    private final TaskExecutor taskExecutor;
    private final TaskStateMachine taskStateMachine;
    private final TaskContext taskContext;
    private final SharedBuffer sharedBuffer;

    private final QueryMonitor queryMonitor;

    private final TaskHandle taskHandle;

    /**
     * Number of drivers that have been sent to the TaskExecutor that have not finished.
     */
    private final AtomicInteger remainingDriverCount = new AtomicInteger();

    @GuardedBy("this")
    private final Set<PlanNodeId> noMoreSplits = new HashSet<>();
    @GuardedBy("this")
    private final Set<PlanNodeId> completedUnpartitionedSources = new HashSet<>();
    @GuardedBy("this")
    private final SetMultimap<PlanNodeId, Split> unpartitionedSources = HashMultimap.create();
    @GuardedBy("this")
    private final List<WeakReference<Driver>> drivers = new ArrayList<>();
    @GuardedBy("this")
    private long maxAcknowledgedSplit = Long.MIN_VALUE;

    private final AtomicReference<DateTime> lastHeartbeat = new AtomicReference<>(DateTime.now());

    private final PlanNodeId partitionedSourceId;
    private final PipelineContext partitionedPipelineContext;
    private final DriverFactory partitionedDriverFactory;
    private final AtomicBoolean noMorePartitionedSplits = new AtomicBoolean();

    private final List<Driver> unpartitionedDrivers;

    private final AtomicLong nextTaskInfoVersion = new AtomicLong(TaskInfo.STARTING_VERSION);

    public static SqlTaskExecution createSqlTaskExecution(Session session,
            TaskId taskId,
            URI location,
            PlanFragment fragment,
            LocalExecutionPlanner planner,
            DataSize maxBufferSize,
            TaskExecutor taskExecutor,
            ExecutorService notificationExecutor,
            DataSize maxTaskMemoryUsage,
            DataSize operatorPreAllocatedMemory,
            QueryMonitor queryMonitor)
    {
        SqlTaskExecution task = new SqlTaskExecution(session,
                taskId,
                location,
                fragment,
                planner,
                maxBufferSize,
                taskExecutor,
                maxTaskMemoryUsage,
                operatorPreAllocatedMemory,
                queryMonitor,
                notificationExecutor
        );

        try (SetThreadName setThreadName = new SetThreadName("Task-%s", taskId)) {
            task.start();
            return task;
        }
    }

    private SqlTaskExecution(Session session,
            TaskId taskId,
            URI location,
            PlanFragment fragment,
            LocalExecutionPlanner planner,
            DataSize maxBufferSize,
            TaskExecutor taskExecutor,
            DataSize maxTaskMemoryUsage,
            DataSize operatorPreAllocatedMemory,
            QueryMonitor queryMonitor,
            Executor notificationExecutor)
    {
        try (SetThreadName setThreadName = new SetThreadName("Task-%s", taskId)) {
            this.taskId = checkNotNull(taskId, "taskId is null");
            this.location = checkNotNull(location, "location is null");
            this.taskExecutor = checkNotNull(taskExecutor, "driverExecutor is null");

            this.taskStateMachine = new TaskStateMachine(taskId, notificationExecutor);
            taskStateMachine.addStateChangeListener(new StateChangeListener<TaskState>()
            {
                @Override
                public void stateChanged(TaskState taskState)
                {
                    if (taskState.isDone()) {
                        SqlTaskExecution.this.taskExecutor.removeTask(taskHandle);
                    }
                }
            });

            this.taskContext = new TaskContext(taskStateMachine,
                    notificationExecutor,
                    session,
                    checkNotNull(maxTaskMemoryUsage, "maxTaskMemoryUsage is null"),
                    checkNotNull(operatorPreAllocatedMemory, "operatorPreAllocatedMemory is null"));

            this.sharedBuffer = new SharedBuffer(checkNotNull(maxBufferSize, "maxBufferSize is null"));

            this.queryMonitor = checkNotNull(queryMonitor, "queryMonitor is null");

            taskHandle = taskExecutor.addTask(taskId);

            LocalExecutionPlan localExecutionPlan = planner.plan(session, fragment.getRoot(), fragment.getSymbols(), new TaskOutputFactory(sharedBuffer));
            List<DriverFactory> driverFactories = localExecutionPlan.getDriverFactories();

            // index driver factories
            DriverFactory partitionedDriverFactory = null;
            List<Driver> unpartitionedDrivers = new ArrayList<>();
            for (DriverFactory driverFactory : driverFactories) {
                if (driverFactory.getSourceIds().contains(fragment.getPartitionedSource())) {
                    partitionedDriverFactory = driverFactory;
                }
                else {
                    PipelineContext pipelineContext = taskContext.addPipelineContext(driverFactory.isInputDriver(), driverFactory.isOutputDriver());
                    Driver driver = driverFactory.createDriver(pipelineContext.addDriverContext());
                    unpartitionedDrivers.add(driver);
                }
            }
            this.unpartitionedDrivers = ImmutableList.copyOf(unpartitionedDrivers);

            checkArgument(!fragment.isPartitioned() || partitionedDriverFactory != null, "Fragment is partitioned, but no partitioned driver found");

            if (partitionedDriverFactory != null) {
                this.partitionedSourceId = fragment.getPartitionedSource();
                this.partitionedDriverFactory = partitionedDriverFactory;
                this.partitionedPipelineContext = taskContext.addPipelineContext(partitionedDriverFactory.isInputDriver(), partitionedDriverFactory.isOutputDriver());
            } else {
                this.partitionedSourceId = null;
                this.partitionedDriverFactory = null;
                this.partitionedPipelineContext = null;
            }
        }
    }

    //
    // This code starts threads so it can not be in the constructor
    private synchronized void start()
    {
        // start unpartitioned drivers
        for (Driver driver : unpartitionedDrivers) {
            drivers.add(new WeakReference<>(driver));
            enqueueDriver(new DriverSplitRunner(driver));
        }
    }

    @Override
    public TaskId getTaskId()
    {
        return taskId;
    }

    @Override
    public void waitForStateChange(TaskState currentState, Duration maxWait)
            throws InterruptedException
    {
        try (SetThreadName setThreadName = new SetThreadName("Task-%s", taskId)) {
            taskStateMachine.waitForStateChange(currentState, maxWait);
        }
    }

    @Override
    public TaskInfo getTaskInfo(boolean full)
    {
        try (SetThreadName setThreadName = new SetThreadName("Task-%s", taskId)) {
            checkTaskCompletion();

            TaskState state = taskStateMachine.getState();
            List<FailureInfo> failures = ImmutableList.of();
            if (state == TaskState.FAILED) {
                failures = toFailures(taskStateMachine.getFailureCauses());
            }

            return new TaskInfo(
                    taskStateMachine.getTaskId(),
                    nextTaskInfoVersion.getAndIncrement(),
                    state,
                    location,
                    lastHeartbeat.get(),
                    sharedBuffer.getInfo(),
                    getNoMoreSplits(),
                    taskContext.getTaskStats(),
                    failures,
                    taskContext.getOutputItems());
        }
    }

    @Override
    public synchronized void addSources(List<TaskSource> sources)
    {
        checkNotNull(sources, "sources is null");

        try (SetThreadName setThreadName = new SetThreadName("Task-%s", taskId)) {
            long newMaxAcknowledgedSplit = maxAcknowledgedSplit;
            for (TaskSource source : sources) {
                PlanNodeId sourceId = source.getPlanNodeId();
                for (ScheduledSplit scheduledSplit : source.getSplits()) {
                    // only add a split if we have not already scheduled it
                    if (scheduledSplit.getSequenceId() > maxAcknowledgedSplit) {
                        addSplit(sourceId, scheduledSplit.getSplit());
                        newMaxAcknowledgedSplit = max(scheduledSplit.getSequenceId(), newMaxAcknowledgedSplit);
                    }
                }
                if (source.isNoMoreSplits()) {
                    noMoreSplits(sourceId);
                }
            }
            maxAcknowledgedSplit = newMaxAcknowledgedSplit;
        }
    }

    @Override
    public synchronized void addResultQueue(OutputBuffers outputIds)
    {
        checkNotNull(outputIds, "outputIds is null");

        try (SetThreadName setThreadName = new SetThreadName("Task-%s", taskId)) {
            for (String bufferId : outputIds.getBufferIds()) {
                sharedBuffer.addQueue(bufferId);
            }
            if (outputIds.isNoMoreBufferIds()) {
                sharedBuffer.noMoreQueues();
            }
        }
    }

    private synchronized void addSplit(PlanNodeId sourceId, final Split split)
    {
        // is this a partitioned source
        if (sourceId.equals(partitionedSourceId)) {
            // create a new driver for the split
            enqueueDriver(new DriverSplitRunner(partitionedPipelineContext.addDriverContext(), new Function<DriverContext, Driver>()
            {
                @Override
                public Driver apply(DriverContext driverContext)
                {
                    return createDriver(partitionedDriverFactory, driverContext, split);
                }
            }));
        }
        else {
            // record the new split so drivers created in the future will see this split
            if (!unpartitionedSources.put(sourceId, split)) {
                return;
            }
            // add split to all of the existing drivers
            for (WeakReference<Driver> driverReference : drivers) {
                Driver driver = driverReference.get();
                // the driver can be GCed due to a failure or a limit
                if (driver != null) {
                    driver.addSplit(sourceId, split);
                }
            }
        }
    }

    private synchronized void enqueueDriver(final DriverSplitRunner splitRunner)
    {
        // schedule driver to be executed
        ListenableFuture<?> finishedFuture = taskExecutor.addSplit(taskHandle, splitRunner);

        // record new driver
        remainingDriverCount.incrementAndGet();

        // when driver completes, update state and fire events
        Futures.addCallback(finishedFuture, new FutureCallback<Object>()
        {
            @Override
            public void onSuccess(Object result)
            {
                try (SetThreadName setThreadName = new SetThreadName("Task-%s", taskId)) {
                    // if all drivers have been created, close the factory so it can perform cleanup
                    int runningCount = remainingDriverCount.decrementAndGet();
                    if (runningCount <= 0) {
                        checkNoMorePartitionedSplits();
                    }

                    checkTaskCompletion();

                    queryMonitor.splitCompletionEvent(taskId, splitRunner.getDriverContext().getDriverStats());
                }
            }

            @Override
            public void onFailure(Throwable cause)
            {
                try (SetThreadName setThreadName = new SetThreadName("Task-%s", taskId)) {
                    taskStateMachine.failed(cause);

                    // record driver is finished
                    remainingDriverCount.decrementAndGet();

                    // check if partitioned driver
                    checkNoMorePartitionedSplits();

                    // todo add failure info to split completion event
                    queryMonitor.splitCompletionEvent(taskId, splitRunner.getDriverContext().getDriverStats());
                }
            }
        });
    }

    private void checkNoMorePartitionedSplits()
    {
        // todo this is not exactly correct, we should be closing when all drivers have been created, but
        // we check against running count which means we are waiting until all drivers are finished
        if (partitionedDriverFactory != null && noMorePartitionedSplits.get() && remainingDriverCount.get() <= 0) {
            partitionedDriverFactory.close();
        }
    }

    private synchronized void noMoreSplits(PlanNodeId sourceId)
    {
        // don't bother updating is this source has already been closed
        if (!noMoreSplits.add(sourceId)) {
            return;
        }

        if (sourceId.equals(partitionedSourceId)) {
            noMorePartitionedSplits.set(true);
            checkNoMorePartitionedSplits();
        }
        else {
            completedUnpartitionedSources.add(sourceId);

            // tell all this existing drivers this source is finished
            for (WeakReference<Driver> driverReference : drivers) {
                Driver driver = driverReference.get();
                // the driver can be GCed due to a failure or a limit
                if (driver != null) {
                    driver.noMoreSplits(sourceId);
                }
            }
        }
    }

    private synchronized Driver createDriver(DriverFactory driverFactory, DriverContext driverContext, Split partitionedSplit)
    {
        Driver driver = driverFactory.createDriver(driverContext);

        if (partitionedSplit != null) {
            // TableScanOperator requires partitioned split to be added before task is started
            driver.addSplit(partitionedSourceId, partitionedSplit);
        }

        // add unpartitioned sources
        for (Entry<PlanNodeId, Split> entry : unpartitionedSources.entries()) {
            driver.addSplit(entry.getKey(), entry.getValue());
        }

        // mark completed sources
        for (PlanNodeId completedUnpartitionedSource : completedUnpartitionedSources) {
            driver.noMoreSplits(completedUnpartitionedSource);
        }

        // record driver so if additional unpartitioned splits are added we can add them to the driver
        drivers.add(new WeakReference<>(driver));
        return driver;
    }

    private synchronized Set<PlanNodeId> getNoMoreSplits()
    {
        return noMoreSplits;
    }

    private synchronized void checkTaskCompletion()
    {
        // are there more partition splits expected?
        if (partitionedSourceId != null && !noMoreSplits.contains(partitionedSourceId)) {
            return;
        }
        // do we still have running tasks?
        if (remainingDriverCount.get() != 0) {
            return;
        }

        // no more output will be created
        sharedBuffer.finish();

        // are there still pages in the output buffer
        if (!sharedBuffer.isFinished()) {
            return;
        }

        // Cool! All done!
        taskStateMachine.finished();
    }

    @Override
    public void cancel()
    {
        try (SetThreadName setThreadName = new SetThreadName("Task-%s", taskId)) {
            taskStateMachine.cancel();
        }
    }

    @Override
    public void fail(Throwable cause)
    {
        try (SetThreadName setThreadName = new SetThreadName("Task-%s", taskId)) {
            taskStateMachine.failed(cause);
        }
    }

    @Override
    public BufferResult getResults(String outputId, long startingSequenceId, DataSize maxSize, Duration maxWait)
            throws InterruptedException
    {
        checkNotNull(outputId, "outputId is null");
        Preconditions.checkArgument(maxSize.toBytes() > 0, "maxSize must be at least 1 byte");
        checkNotNull(maxWait, "maxWait is null");

        try (SetThreadName setThreadName = new SetThreadName("Task-%s", taskId)) {
            return sharedBuffer.get(outputId, startingSequenceId, maxSize, maxWait);
        }
    }

    @Override
    public void abortResults(String outputId)
    {
        try (SetThreadName setThreadName = new SetThreadName("Task-%s", taskId)) {
            sharedBuffer.abort(outputId);
        }
    }

    @Override
    public void recordHeartbeat()
    {
        this.lastHeartbeat.set(DateTime.now());
    }

    @Override
    public String toString()
    {
        return Objects.toStringHelper(this)
                .add("taskId", taskId)
                .add("unpartitionedSources", unpartitionedSources)
                .toString();
    }

    private static class DriverSplitRunner
            implements SplitRunner
    {
        private final DriverContext driverContext;
        private final Function<? super DriverContext, Driver> driverSupplier;
        private Driver driver;

        public DriverSplitRunner(Driver driver)
        {
            this(driver.getDriverContext(), Functions.constant(driver));
        }

        private DriverSplitRunner(DriverContext driverContext, Function<? super DriverContext, Driver> driverFactory)
        {
            this.driverContext = checkNotNull(driverContext, "driverContext is null");
            this.driverSupplier = checkNotNull(driverFactory, "driverFactory is null");
        }

        public DriverContext getDriverContext()
        {
            return driverContext;
        }

        @Override
        public synchronized void initialize()
        {
            driver = driverSupplier.apply(driverContext);
        }

        @Override
        public synchronized boolean isFinished()
        {
            return driver.isFinished();
        }

        @Override
        public ListenableFuture<?> processFor(Duration duration)
        {
            return driver.processFor(duration);
        }
    }
}