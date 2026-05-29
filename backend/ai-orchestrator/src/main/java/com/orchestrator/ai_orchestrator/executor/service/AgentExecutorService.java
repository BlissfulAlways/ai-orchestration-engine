package com.orchestrator.ai_orchestrator.executor.service;

import com.orchestrator.ai_orchestrator.executor.domain.AgentExecutionStep;
import com.orchestrator.ai_orchestrator.executor.domain.ToolDefinition;
import com.orchestrator.ai_orchestrator.executor.infrastructure.AgentExecutionStepRepository;
import com.orchestrator.ai_orchestrator.executor.infrastructure.ToolDefinitionRepository;
import com.orchestrator.ai_orchestrator.aggregator.service.AggregatorTriggerService;
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
import java.util.Optional;

@Slf4j
@Service
@RequiredArgsConstructor
public class AgentExecutorService {

    private static final int MAX_RETRY_COUNT = 3;
    private static final int STUCK_TASK_TIMEOUT_MINUTES = 5;

    private final TaskQueueRepository taskQueueRepository;
    private final PlannedTaskRepository plannedTaskRepository;
    private final ToolDefinitionRepository toolDefinitionRepository;
    private final AgentExecutionStepRepository agentExecutionStepRepository;
    private final TaskResultRepository taskResultRepository;
    private final AgentLoopService agentLoopService;
    private final ObserverService observerService;
    private final AggregatorTriggerService aggregatorTriggerService;

    @Scheduled(fixedDelay = 5000)
    public void pollAndExecute() {
        for (String agentType : List.of("WEB_SEARCH_AGENT", "SUMMARIZER_AGENT", "WRITER_AGENT")) {
            Optional<QueuedTask> pendingTask = taskQueueRepository
                    .findFirstByStatusAndRequiredAgentTypeOrderByQueuedAtAsc(
                            QueuedTaskStatus.PENDING, agentType);

            if (pendingTask.isEmpty()) continue;

            QueuedTask task = pendingTask.get();
            task.setStatus(QueuedTaskStatus.PROCESSING);
            task.setPickedUpAt(LocalDateTime.now());
            taskQueueRepository.save(task);

            executeTask(task);
        }
    }

    @Scheduled(fixedDelay = 60000)
    public void rescueStuckTasks() {
        List<QueuedTask> allProcessingTasks = taskQueueRepository.findByJobId(null);

        taskQueueRepository.findAll().stream()
                .filter(t -> t.getStatus() == QueuedTaskStatus.PROCESSING)
                .filter(t -> t.getPickedUpAt() != null &&
                        t.getPickedUpAt().isBefore(LocalDateTime.now().minusMinutes(STUCK_TASK_TIMEOUT_MINUTES)))
                .forEach(stuckTask -> {
                    if (stuckTask.getRetryCount() < MAX_RETRY_COUNT) {
                        stuckTask.setStatus(QueuedTaskStatus.PENDING);
                        stuckTask.setRetryCount(stuckTask.getRetryCount() + 1);
                        stuckTask.setPickedUpAt(null);
                        log.warn("Rescued stuck task id={} retryCount={}", stuckTask.getId(), stuckTask.getRetryCount());
                    } else {
                        stuckTask.setStatus(QueuedTaskStatus.FAILED);
                        writeFailedResult(stuckTask, "EXECUTOR_TIMEOUT");
                        log.error("Task permanently failed after max retries id={}", stuckTask.getId());
                    }
                    taskQueueRepository.save(stuckTask);
                });
    }

    private void executeTask(QueuedTask queuedTask) {
        try {
            observerService.emit("AGENT_EXECUTOR", "TASK_EXECUTION_STARTED",
                    queuedTask.getJobId(),
                    Map.of("taskQueueId", queuedTask.getId().toString()));

            Optional<PlannedTask> plannedTaskOpt = plannedTaskRepository
                    .findById(queuedTask.getPlannedTaskId());

            if (plannedTaskOpt.isEmpty()) {
                markTaskFailed(queuedTask, "PLANNED_TASK_NOT_FOUND");
                return;
            }

            PlannedTask plannedTask = plannedTaskOpt.get();
            List<ToolDefinition> availableTools = toolDefinitionRepository.findByIsActiveTrue();
            List<AgentExecutionStep> previousSteps = agentExecutionStepRepository
                    .findByTaskQueueIdOrderByStepNumberAsc(queuedTask.getId());

            String finalAnswer = agentLoopService.runLoop(
                    queuedTask.getJobId(),
                    queuedTask.getId(),
                    plannedTask.getTaskDescription(),
                    availableTools,
                    previousSteps
            );

            TaskResult result = TaskResult.builder()
                    .jobId(queuedTask.getJobId())
                    .plannedTaskId(queuedTask.getPlannedTaskId())
                    .status(TaskResultStatus.COMPLETED)
                    .resultContent(finalAnswer)
                    .failureReason(null)
                    .createdAt(LocalDateTime.now())
                    .updatedAt(LocalDateTime.now())
                    .build();

            taskResultRepository.save(result);

            queuedTask.setStatus(QueuedTaskStatus.COMPLETED);
            taskQueueRepository.save(queuedTask);

            observerService.emit("AGENT_EXECUTOR", "TASK_EXECUTION_COMPLETED",
                    queuedTask.getJobId(),
                    Map.of("taskQueueId", queuedTask.getId().toString()));

            aggregatorTriggerService.checkAndTrigger(queuedTask.getJobId());

        } catch (Exception e) {
            log.error("Task execution failed taskQueueId={} error={}", queuedTask.getId(), e.getMessage());

            if (queuedTask.getRetryCount() < MAX_RETRY_COUNT) {
                queuedTask.setStatus(QueuedTaskStatus.PENDING);
                queuedTask.setRetryCount(queuedTask.getRetryCount() + 1);
                queuedTask.setPickedUpAt(null);
                taskQueueRepository.save(queuedTask);
                log.warn("Task reset to PENDING for retry retryCount={}", queuedTask.getRetryCount());
            } else {
                markTaskFailed(queuedTask, e.getMessage());
                aggregatorTriggerService.checkAndTrigger(queuedTask.getJobId());
            }
        }
    }

    private void markTaskFailed(QueuedTask queuedTask, String reason) {
        queuedTask.setStatus(QueuedTaskStatus.FAILED);
        taskQueueRepository.save(queuedTask);
        writeFailedResult(queuedTask, reason);
        observerService.emit("AGENT_EXECUTOR", "TASK_EXECUTION_FAILED",
                queuedTask.getJobId(),
                Map.of("taskQueueId", queuedTask.getId().toString(), "reason", reason));
    }

    private void writeFailedResult(QueuedTask queuedTask, String reason) {
        TaskResult result = TaskResult.builder()
                .jobId(queuedTask.getJobId())
                .plannedTaskId(queuedTask.getPlannedTaskId())
                .status(TaskResultStatus.FAILED)
                .resultContent(null)
                .failureReason(reason)
                .createdAt(LocalDateTime.now())
                .updatedAt(LocalDateTime.now())
                .build();
        taskResultRepository.save(result);
    }
}
