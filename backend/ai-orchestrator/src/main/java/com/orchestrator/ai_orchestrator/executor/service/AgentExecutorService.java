package com.orchestrator.ai_orchestrator.executor.service;

import com.orchestrator.ai_orchestrator.aggregator.service.AggregatorTriggerService;
import com.orchestrator.ai_orchestrator.common.AgentTypes;
import com.orchestrator.ai_orchestrator.common.ErrorMessages;
import com.orchestrator.ai_orchestrator.executor.domain.AgentExecutionStep;
import com.orchestrator.ai_orchestrator.executor.domain.ToolDefinition;
import com.orchestrator.ai_orchestrator.executor.infrastructure.AgentExecutionStepRepository;
import com.orchestrator.ai_orchestrator.executor.infrastructure.ToolDefinitionRepository;
import com.orchestrator.ai_orchestrator.llmgateway.service.CircuitOpenException;
import com.orchestrator.ai_orchestrator.llmgateway.service.LlmCircuitBreaker;
import com.orchestrator.ai_orchestrator.llmgateway.service.LlmException;
import com.orchestrator.ai_orchestrator.observer.service.ObserverService;
import com.orchestrator.ai_orchestrator.planner.domain.PlannedTask;
import com.orchestrator.ai_orchestrator.planner.infrastructure.PlannedTaskRepository;
import com.orchestrator.ai_orchestrator.resultstore.domain.TaskResult;
import com.orchestrator.ai_orchestrator.resultstore.domain.TaskResultStatus;
import com.orchestrator.ai_orchestrator.resultstore.infrastructure.TaskResultRepository;
import com.orchestrator.ai_orchestrator.taskqueue.domain.QueuedTask;
import com.orchestrator.ai_orchestrator.taskqueue.infrastructure.TaskQueueRepository;
import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.slf4j.MDC;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Pulls tasks from the queue and runs them on a bounded pool of workers.
 *
 * <pre>
 *   pollAndDispatch   every 2 s   claims tasks while a worker slot is free, hands each to a worker
 *   sendHeartbeats    every 30 s  tells the database "these tasks are still running"
 *   rescueStuckTasks  every 30 s  re-queues tasks whose heartbeat went silent (worker died)
 * </pre>
 *
 * <p>The poller only DISPATCHES; it never runs a task itself, so the scheduler thread is never
 * blocked and the three jobs above keep running on time.
 *
 * <p>Concurrency is capped by {@code orchestrator.executor.max-workers} (default 3), because
 * every running task makes model calls and the provider limits how many are allowed at once.
 *
 * <p>While the Gemini circuit breaker is open the poller starts nothing: tasks wait in the
 * queue, and a task that is caught mid-run by an outage goes back to the queue WITHOUT using up
 * one of its retries.
 */
@Slf4j
@Service
public class AgentExecutorService {

    private static final int MAX_RETRY_COUNT = 3;
    private static final long SHUTDOWN_WAIT_SECONDS = 30;

    private final TaskQueueRepository taskQueueRepository;
    private final PlannedTaskRepository plannedTaskRepository;
    private final ToolDefinitionRepository toolDefinitionRepository;
    private final AgentExecutionStepRepository agentExecutionStepRepository;
    private final TaskResultRepository taskResultRepository;
    private final AgentLoopService agentLoopService;
    private final ObserverService observerService;
    private final AggregatorTriggerService aggregatorTriggerService;
    private final TaskOutcomeService taskOutcomeService;
    private final LlmCircuitBreaker circuitBreaker;

    private final int staleTaskSeconds;
    /** One permit per worker. A permit is taken when a task is claimed and returned when it ends. */
    private final Semaphore workerSlots;
    private final ExecutorService workers = Executors.newVirtualThreadPerTaskExecutor();
    /** Queue ids of the tasks running inside this process right now. */
    private final Set<UUID> runningTaskIds = ConcurrentHashMap.newKeySet();
    private volatile boolean acceptingWork = true;

    public AgentExecutorService(TaskQueueRepository taskQueueRepository,
                                PlannedTaskRepository plannedTaskRepository,
                                ToolDefinitionRepository toolDefinitionRepository,
                                AgentExecutionStepRepository agentExecutionStepRepository,
                                TaskResultRepository taskResultRepository,
                                AgentLoopService agentLoopService,
                                ObserverService observerService,
                                AggregatorTriggerService aggregatorTriggerService,
                                TaskOutcomeService taskOutcomeService,
                                LlmCircuitBreaker circuitBreaker,
                                @Value("${orchestrator.executor.max-workers:3}") int maxWorkers,
                                @Value("${orchestrator.executor.stale-task-seconds:120}") int staleTaskSeconds) {
        this.taskQueueRepository = taskQueueRepository;
        this.plannedTaskRepository = plannedTaskRepository;
        this.toolDefinitionRepository = toolDefinitionRepository;
        this.agentExecutionStepRepository = agentExecutionStepRepository;
        this.taskResultRepository = taskResultRepository;
        this.agentLoopService = agentLoopService;
        this.observerService = observerService;
        this.aggregatorTriggerService = aggregatorTriggerService;
        this.taskOutcomeService = taskOutcomeService;
        this.circuitBreaker = circuitBreaker;
        this.staleTaskSeconds = staleTaskSeconds;
        this.workerSlots = new Semaphore(Math.max(1, maxWorkers));
    }

    // ---------------------------------------------------------------- dispatching

    @Scheduled(fixedDelayString = "${orchestrator.executor.poll-interval-ms:2000}",
            initialDelayString = "${orchestrator.executor.initial-delay-ms:0}")
    public void pollAndDispatch() {
        // Gemini is refusing us: do not start work that is certain to fail. Tasks stay
        // PENDING in the database and start again once the breaker lets a probe through.
        if (circuitBreaker.isOpen()) {
            return;
        }

        boolean claimedAny;
        do {
            claimedAny = false;
            for (String agentType : AgentTypes.ALL) {
                // No free worker (or shutting down): stop. Waiting tasks stay PENDING in the database.
                if (!acceptingWork || !workerSlots.tryAcquire()) {
                    return;
                }
                QueuedTask task = claimNext(agentType);
                if (task == null) {
                    workerSlots.release(); // Nothing to run, give the slot back.
                    continue;
                }
                dispatch(task);
                claimedAny = true;
            }
        } while (claimedAny); // Keep going until the workers are full or the queue is empty.
    }

    private QueuedTask claimNext(String agentType) {
        try {
            return taskQueueRepository.atomicPickup(agentType, Instant.now()).orElse(null);
        } catch (Exception e) {
            // One failing agent type must not stop the others from being polled.
            log.error("Polling failed for agentType={}", agentType, e);
            return null;
        }
    }

    private void dispatch(QueuedTask task) {
        runningTaskIds.add(task.getId());
        try {
            workers.execute(() -> {
                // Every log line written by this worker now carries the job and task ids.
                MDC.put("jobId", String.valueOf(task.getJobId()));
                MDC.put("taskQueueId", String.valueOf(task.getId()));
                try {
                    executeTask(task);
                } finally {
                    MDC.clear();
                    runningTaskIds.remove(task.getId());
                    workerSlots.release();
                }
            });
        } catch (RuntimeException e) {
            // The worker could not start (for example during shutdown). The row stays
            // PROCESSING; its heartbeat will go quiet and the rescuer will re-queue it.
            runningTaskIds.remove(task.getId());
            workerSlots.release();
            log.error("Could not start worker for taskQueueId={}", task.getId(), e);
        }
    }

    // ---------------------------------------------------------------- heartbeat and rescue

    @Scheduled(fixedDelayString = "${orchestrator.executor.heartbeat-interval-ms:30000}")
    public void sendHeartbeats() {
        if (runningTaskIds.isEmpty()) {
            return;
        }
        try {
            taskQueueRepository.touchHeartbeat(List.copyOf(runningTaskIds), Instant.now());
        } catch (Exception e) {
            log.error("Could not send heartbeats", e);
        }
    }

    /** Re-queues tasks whose worker disappeared, or fails them after too many attempts. */
    @Scheduled(fixedDelayString = "${orchestrator.executor.rescue-interval-ms:30000}")
    public void rescueStuckTasks() {
        Instant cutoff = Instant.now().minusSeconds(staleTaskSeconds);

        for (QueuedTask stuckTask : taskQueueRepository.findStaleProcessingTasks(cutoff)) {
            if (runningTaskIds.contains(stuckTask.getId())) {
                continue; // Alive in this process; only the heartbeat write was late.
            }
            try {
                if (stuckTask.getRetryCount() < MAX_RETRY_COUNT) {
                    taskOutcomeService.scheduleRetry(stuckTask);
                    log.warn("Rescued stuck task id={} retryCount={}",
                            stuckTask.getId(), stuckTask.getRetryCount());
                } else {
                    taskOutcomeService.recordFailure(stuckTask, "EXECUTOR_TIMEOUT");
                    log.error("Task permanently failed after max retries id={}", stuckTask.getId());
                    emitTaskFailed(stuckTask, "EXECUTOR_TIMEOUT");
                    // Without this the job would wait forever for a result that will never come.
                    triggerAggregation(stuckTask.getJobId());
                }
            } catch (Exception e) {
                log.error("Failed to rescue stuck task id={}", stuckTask.getId(), e);
            }
        }
    }

    // ---------------------------------------------------------------- running one task

    private void executeTask(QueuedTask queuedTask) {
        boolean taskSettled;
        try {
            observerService.emit("AGENT_EXECUTOR", "TASK_EXECUTION_STARTED",
                    queuedTask.getJobId(),
                    Map.of("taskQueueId", queuedTask.getId().toString()));

            String finalAnswer = runAgent(queuedTask);

            taskOutcomeService.recordSuccess(queuedTask, finalAnswer);
            observerService.emit("AGENT_EXECUTOR", "TASK_EXECUTION_COMPLETED",
                    queuedTask.getJobId(),
                    Map.of("taskQueueId", queuedTask.getId().toString()));
            taskSettled = true;
        } catch (Exception e) {
            if (isProviderOutage(e)) {
                // Not the task's fault: wait for Gemini to recover, keep the retry budget.
                log.warn("Gemini unavailable; returning task to the queue without a retry penalty "
                        + "taskQueueId={} jobId={}", queuedTask.getId(), queuedTask.getJobId());
                taskSettled = releaseWithoutPenalty(queuedTask);
            } else {
                log.error("Task execution failed taskQueueId={} jobId={}",
                        queuedTask.getId(), queuedTask.getJobId(), e);
                taskSettled = handleFailure(queuedTask, e);
            }
        }

        // Deliberately OUTSIDE the try block: a failure while checking for aggregation must
        // never be mistaken for a task failure and cause a finished task to run again.
        if (taskSettled) {
            triggerAggregation(queuedTask.getJobId());
        }
    }

    private String runAgent(QueuedTask queuedTask) {
        PlannedTask plannedTask = plannedTaskRepository
                .findById(queuedTask.getPlannedTaskId())
                .orElseThrow(() -> new PermanentTaskFailure(
                        "PLANNED_TASK_NOT_FOUND: " + queuedTask.getPlannedTaskId()));

        List<ToolDefinition> availableTools = toolDefinitionRepository.findByIsActiveTrue();
        List<AgentExecutionStep> previousSteps = agentExecutionStepRepository
                .findByTaskQueueIdOrderByStepNumberAsc(queuedTask.getId());
        String priorTaskContext = buildPriorTaskContext(
                queuedTask.getJobId(), plannedTask.getSequenceNumber());

        return agentLoopService.runLoop(
                queuedTask.getJobId(),
                queuedTask.getId(),
                plannedTask.getTaskDescription(),
                availableTools,
                previousSteps,
                priorTaskContext);
    }

    /**
     * Decides between "try again" and "give up".
     *
     * @return true if the task is now settled (permanently failed), false if it will be retried
     *         or if the bookkeeping itself failed
     */
    private boolean handleFailure(QueuedTask queuedTask, Exception error) {
        boolean canRetry = !isPermanent(error) && queuedTask.getRetryCount() < MAX_RETRY_COUNT;
        try {
            if (canRetry) {
                taskOutcomeService.scheduleRetry(queuedTask);
                log.warn("Task reset to PENDING for retry taskQueueId={} retryCount={}",
                        queuedTask.getId(), queuedTask.getRetryCount());
                return false;
            }
            String reason = ErrorMessages.describe(error);
            taskOutcomeService.recordFailure(queuedTask, reason);
            emitTaskFailed(queuedTask, reason);
            return true;
        } catch (Exception bookkeepingError) {
            // The database itself is failing. The row stays PROCESSING; the heartbeat stops and
            // the rescuer picks it up after the timeout.
            log.error("Could not record failure for taskQueueId={}", queuedTask.getId(), bookkeepingError);
            return false;
        }
    }

    /**
     * True when the failure means "Gemini cannot serve us right now": the breaker refused the
     * call, or a retryable model error happened while the breaker is open.
     */
    private boolean isProviderOutage(Throwable error) {
        for (Throwable current = error; current != null; current = current.getCause()) {
            if (current instanceof CircuitOpenException) {
                return true;
            }
            if (current instanceof LlmException llmError
                    && llmError.isRetryable() && circuitBreaker.isOpen()) {
                return true;
            }
            if (current.getCause() == current) {
                break;
            }
        }
        return false;
    }

    /** Returns the task to the queue with its retry count unchanged. Always "not settled". */
    private boolean releaseWithoutPenalty(QueuedTask queuedTask) {
        try {
            taskOutcomeService.releaseForLater(queuedTask);
        } catch (Exception bookkeepingError) {
            // The row stays PROCESSING; the rescuer will handle it after the heartbeat timeout.
            log.error("Could not release taskQueueId={}", queuedTask.getId(), bookkeepingError);
        }
        return false;
    }

    /**
     * True when retrying cannot help: a missing planned task, or a model error marked
     * non-retryable (bad API key, blocked prompt). Looks through wrapped exceptions too.
     */
    private boolean isPermanent(Throwable error) {
        for (Throwable current = error; current != null; current = current.getCause()) {
            if (current instanceof PermanentTaskFailure) {
                return true;
            }
            if (current instanceof LlmException llmError && !llmError.isRetryable()) {
                return true;
            }
            if (current.getCause() == current) {
                break; // Guard against a cause that points at itself.
            }
        }
        return false;
    }

    private void triggerAggregation(UUID jobId) {
        try {
            aggregatorTriggerService.checkAndTrigger(jobId);
        } catch (Exception e) {
            log.error("Aggregation check failed jobId={}", jobId, e);
        }
    }

    private void emitTaskFailed(QueuedTask queuedTask, String reason) {
        observerService.emit("AGENT_EXECUTOR", "TASK_EXECUTION_FAILED",
                queuedTask.getJobId(),
                Map.of("taskQueueId", queuedTask.getId().toString(), "reason", reason));
    }

    /**
     * Builds the "results so far" text for a task from the earlier tasks of the same job.
     * Returns null for the first task or when nothing earlier completed.
     */
    private String buildPriorTaskContext(UUID jobId, int currentSequenceNumber) {
        if (currentSequenceNumber <= 1) {
            return null;
        }

        Map<UUID, PlannedTask> taskById = plannedTaskRepository
                .findByJobIdOrderBySequenceNumberAsc(jobId).stream()
                .collect(Collectors.toMap(PlannedTask::getId, Function.identity()));

        StringBuilder context = new StringBuilder();
        for (TaskResult result : taskResultRepository.findByJobId(jobId)) {
            if (result.getStatus() != TaskResultStatus.COMPLETED || result.getResultContent() == null) {
                continue;
            }
            PlannedTask task = taskById.get(result.getPlannedTaskId());
            if (task != null && task.getSequenceNumber() < currentSequenceNumber) {
                context.append("Task ").append(task.getSequenceNumber())
                        .append(" (").append(task.getRequiredAgentType()).append("): ")
                        .append(task.getTaskDescription()).append("\n")
                        .append("Result: ").append(result.getResultContent()).append("\n\n");
            }
        }
        return context.length() > 0 ? context.toString() : null;
    }

    // ---------------------------------------------------------------- shutdown

    /**
     * Stops taking new work and gives running tasks up to 30 s to finish. Anything still
     * running is left as PROCESSING; its heartbeat stops, and after a restart the rescuer
     * puts it back in the queue.
     */
    @PreDestroy
    public void shutdown() {
        acceptingWork = false;
        workers.shutdown();
        try {
            if (!workers.awaitTermination(SHUTDOWN_WAIT_SECONDS, TimeUnit.SECONDS)) {
                log.warn("{} task(s) still running at shutdown; they will be re-queued after restart",
                        runningTaskIds.size());
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    /** A failure that retrying cannot fix. Skips the retry budget and fails the task at once. */
    private static final class PermanentTaskFailure extends RuntimeException {
        PermanentTaskFailure(String message) {
            super(message);
        }
    }
}