package com.orchestrator.ai_orchestrator.apigateway.service;

import com.orchestrator.ai_orchestrator.apigateway.infrastructure.JobRepository;
import com.orchestrator.ai_orchestrator.executor.domain.AgentExecutionStep;
import com.orchestrator.ai_orchestrator.executor.infrastructure.AgentExecutionStepRepository;
import com.orchestrator.ai_orchestrator.planner.domain.PlannedTask;
import com.orchestrator.ai_orchestrator.planner.infrastructure.PlannedTaskRepository;
import com.orchestrator.ai_orchestrator.resultstore.domain.TaskResult;
import com.orchestrator.ai_orchestrator.resultstore.infrastructure.TaskResultRepository;
import com.orchestrator.ai_orchestrator.taskqueue.domain.QueuedTask;
import com.orchestrator.ai_orchestrator.taskqueue.infrastructure.TaskQueueRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Builds the execution trace of a job: every planned task with its status, result, and the
 * steps the agent took. Four queries in total, however many tasks the job has.
 */
@Service
@RequiredArgsConstructor
public class JobTraceService {

    private final JobRepository jobRepository;
    private final PlannedTaskRepository plannedTaskRepository;
    private final TaskResultRepository taskResultRepository;
    private final TaskQueueRepository taskQueueRepository;
    private final AgentExecutionStepRepository agentExecutionStepRepository;

    /** One agent step. Text fields are never null: a missing value is an empty string. */
    public record StepTrace(int stepNumber, String agentThought, String toolCalled, String toolResult) {}

    /** One planned task and what happened to it. */
    public record TaskTrace(
            int sequenceNumber,
            String taskDescription,
            String requiredAgentType,
            String status,
            String resultContent,
            String failureReason,
            List<StepTrace> steps) {}

    /**
     * @return empty if the job does not exist; otherwise the tasks in plan order
     *         (an empty list if the job has not been planned yet)
     */
    @Transactional(readOnly = true)
    public Optional<List<TaskTrace>> getTrace(UUID jobId, String userId) {
        // Another user's job is reported as "not found", exactly like a job that does not exist.
        if (!jobRepository.existsByIdAndUserId(jobId, userId)) {
            return Optional.empty();
        }

        List<PlannedTask> plannedTasks = plannedTaskRepository.findByJobIdOrderBySequenceNumberAsc(jobId);

        // The (first, second) -> first part keeps the first entry if a key ever repeats,
        // instead of throwing IllegalStateException.
        Map<UUID, TaskResult> resultByTaskId = taskResultRepository.findByJobId(jobId).stream()
                .collect(Collectors.toMap(TaskResult::getPlannedTaskId, Function.identity(), (first, second) -> first));
        Map<UUID, QueuedTask> queueEntryByTaskId = taskQueueRepository.findByJobId(jobId).stream()
                .collect(Collectors.toMap(QueuedTask::getPlannedTaskId, Function.identity(), (first, second) -> first));

        // All steps of the job, grouped by the queue entry they belong to. Within each group
        // the steps keep the step-number order the query returned.
        Map<UUID, List<AgentExecutionStep>> stepsByQueueId =
                agentExecutionStepRepository.findByJobIdOrderByStepNumberAsc(jobId).stream()
                        .collect(Collectors.groupingBy(AgentExecutionStep::getTaskQueueId));

        List<TaskTrace> trace = plannedTasks.stream()
                .map(task -> toTaskTrace(task, resultByTaskId.get(task.getId()),
                        queueEntryByTaskId.get(task.getId()), stepsByQueueId))
                .toList();
        return Optional.of(trace);
    }

    private TaskTrace toTaskTrace(PlannedTask task, TaskResult result, QueuedTask queueEntry,
                                  Map<UUID, List<AgentExecutionStep>> stepsByQueueId) {
        // A result is final. Without one, the queue row says whether the task is waiting or running.
        String status = result != null ? result.getStatus().name()
                : queueEntry != null ? queueEntry.getStatus().name()
                : "PENDING";

        List<StepTrace> steps = queueEntry == null ? List.of()
                : stepsByQueueId.getOrDefault(queueEntry.getId(), List.of()).stream()
                        .map(step -> new StepTrace(
                                step.getStepNumber(),
                                orEmpty(step.getAgentThought()),
                                orEmpty(step.getToolCalled()),
                                orEmpty(step.getToolResult())))
                        .toList();

        return new TaskTrace(
                task.getSequenceNumber(),
                task.getTaskDescription(),
                task.getRequiredAgentType(),
                status,
                result == null ? "" : orEmpty(result.getResultContent()),
                result == null ? "" : orEmpty(result.getFailureReason()),
                steps);
    }

    private static String orEmpty(String value) {
        return value == null ? "" : value;
    }
}