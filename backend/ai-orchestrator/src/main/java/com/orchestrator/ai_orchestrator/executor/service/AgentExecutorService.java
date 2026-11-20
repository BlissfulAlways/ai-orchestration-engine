package com.orchestrator.ai_orchestrator.executor.service;

import com.orchestrator.ai_orchestrator.aggregator.service.AggregatorTriggerService;
import com.orchestrator.ai_orchestrator.common.ErrorMessages;
import com.orchestrator.ai_orchestrator.executor.domain.AgentExecutionStep;
import com.orchestrator.ai_orchestrator.executor.domain.ToolDefinition;
import com.orchestrator.ai_orchestrator.executor.infrastructure.AgentExecutionStepRepository;
import com.orchestrator.ai_orchestrator.executor.infrastructure.ToolDefinitionRepository;
import com.orchestrator.ai_orchestrator.observer.service.ObserverService;
import com.orchestrator.ai_orchestrator.planner.domain.PlannedTask;
import com.orchestrator.ai_orchestrator.planner.infrastructure.PlannedTaskRepository;
import com.orchestrator.ai_orchestrator.resultstore.domain.TaskResult;
import com.orchestrator.ai_orchestrator.resultstore.domain.TaskResultStatus;
import com.orchestrator.ai_orchestrator.resultstore.infrastructure.TaskResultRepository;
import com.orchestrator.ai_orchestrator.taskqueue.domain.QueuedTask;
import com.orchestrator.ai_orchestrator.taskqueue.domain.QueuedTaskStatus;
import com.orchestrator.ai_orchestrator.taskqueue.infrastructure.TaskQueueRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Pulls tasks from the queue, runs them through the agent loop, and records the outcome.
 *
 * <p>Known limitation (fixed in Phase 3): polling, execution and the stuck-task rescuer all
 * share Spring's default single scheduler thread, so the rescuer cannot run while a task runs.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AgentExecutorService {

    private static final int MAX_RETRY_COUNT = 3;
    private static final int STUCK_TASK_TIMEOUT_MINUTES = 5;
    private static final List<String> AGENT_TYPES = List.of("RESEARCH_AGENT", "WRITER_AGENT");

    private final TaskQueueRepository taskQueueRepository;
    private final PlannedTaskRepository plannedTaskRepository;
    private final ToolDefinitionRepository toolDefinitionRepository;
    private final AgentExecutionStepRepository agentExecutionStepRepository;
    private final TaskResultRepository taskResultRepository;
    private final AgentLoopService agentLoopService;
    private final ObserverService observerService;
    private final AggregatorTriggerService aggregatorTriggerService;
    private final TaskOutcomeService taskOutcomeService;

    /** Claims and runs at most one task per agent type on every tick. */
    @Scheduled(fixedDelay = 5000)
    public void pollAndExecute() {
        for (String agentType : AGENT_TYPES) {
            try {
                taskQueueRepository
                        .atomicPickup(agentType, LocalDateTime.now())
                        .ifPresent(this::executeTask);
            } catch (Exception e) {
                // One failing agent type must not stop the others from being polled.
                log.error("Polling failed for agentType={}", agentType, e);
            }
        }
    }

    /** Re-queues tasks whose worker disappeared, or fails them after too many attempts. */
    @Scheduled(fixedDelay = 60000)
    public void rescueStuckTasks() {
        LocalDateTime cutoff = LocalDateTime.now().minusMinutes(STUCK_TASK_TIMEOUT_MINUTES);
        List<QueuedTask> stuckTasks = taskQueueRepository
                .findByStatusAndPickedUpAtBefore(QueuedTaskStatus.PROCESSING, cutoff);

        for (QueuedTask stuckTask : stuckTasks) {
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
            log.error("Task execution failed taskQueueId={} jobId={}",
                    queuedTask.getId(), queuedTask.getJobId(), e);
            taskSettled = handleFailure(queuedTask, e);
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
        boolean canRetry = !(error instanceof PermanentTaskFailure)
                && queuedTask.getRetryCount() < MAX_RETRY_COUNT;
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
            // The database itself is failing. The row stays PROCESSING and the stuck-task
            // rescuer will pick it up after the timeout.
            log.error("Could not record failure for taskQueueId={}", queuedTask.getId(), bookkeepingError);
            return false;
        }
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

    /** A failure that retrying cannot fix. Skips the retry budget and fails the task at once. */
    private static final class PermanentTaskFailure extends RuntimeException {
        PermanentTaskFailure(String message) {
            super(message);
        }
    }
}