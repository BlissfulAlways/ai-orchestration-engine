package com.orchestrator.ai_orchestrator.apigateway.infrastructure;

import com.orchestrator.ai_orchestrator.executor.domain.AgentExecutionStep;
import com.orchestrator.ai_orchestrator.executor.infrastructure.AgentExecutionStepRepository;
import com.orchestrator.ai_orchestrator.planner.domain.PlannedTask;
import com.orchestrator.ai_orchestrator.planner.infrastructure.PlannedTaskRepository;
import com.orchestrator.ai_orchestrator.resultstore.domain.TaskResult;
import com.orchestrator.ai_orchestrator.resultstore.infrastructure.TaskResultRepository;
import com.orchestrator.ai_orchestrator.taskqueue.domain.QueuedTask;
import com.orchestrator.ai_orchestrator.taskqueue.infrastructure.TaskQueueRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

@RestController
@RequestMapping("/api/jobs")
@RequiredArgsConstructor
public class JobTraceController {

    private final PlannedTaskRepository plannedTaskRepository;
    private final TaskResultRepository taskResultRepository;
    private final TaskQueueRepository taskQueueRepository;
    private final AgentExecutionStepRepository agentExecutionStepRepository;

    @GetMapping("/{jobId}/trace")
    public ResponseEntity<List<Map<String, Object>>> getJobTrace(@PathVariable UUID jobId) {

        List<PlannedTask> plannedTasks = plannedTaskRepository
                .findByJobIdOrderBySequenceNumberAsc(jobId);

        if (plannedTasks.isEmpty()) {
            return ResponseEntity.notFound().build();
        }

        List<TaskResult> allResults = taskResultRepository.findByJobId(jobId);
        List<QueuedTask> allQueuedTasks = taskQueueRepository.findByJobId(jobId);

        Map<UUID, TaskResult> resultByPlannedTaskId = allResults.stream()
                .collect(Collectors.toMap(TaskResult::getPlannedTaskId, r -> r,
                        (a, b) -> a));

        Map<UUID, QueuedTask> queuedTaskByPlannedTaskId = allQueuedTasks.stream()
                .collect(Collectors.toMap(QueuedTask::getPlannedTaskId, q -> q,
                        (a, b) -> a));

        List<Map<String, Object>> trace = plannedTasks.stream().map(task -> {
            TaskResult result = resultByPlannedTaskId.get(task.getId());
            QueuedTask queuedTask = queuedTaskByPlannedTaskId.get(task.getId());

            List<Map<String, Object>> steps = List.of();
            if (queuedTask != null) {
                steps = agentExecutionStepRepository
                        .findByTaskQueueIdOrderByStepNumberAsc(queuedTask.getId())
                        .stream()
                        .map(step -> Map.<String, Object>of(
                                "stepNumber", step.getStepNumber(),
                                "agentThought", step.getAgentThought(),
                                "toolCalled", step.getToolCalled() != null ? step.getToolCalled() : "",
                                "toolResult", step.getToolResult() != null ? step.getToolResult() : ""
                        ))
                        .collect(Collectors.toList());
            }

            return Map.<String, Object>of(
                    "sequenceNumber", task.getSequenceNumber(),
                    "taskDescription", task.getTaskDescription(),
                    "requiredAgentType", task.getRequiredAgentType(),
                    "status", result != null ? result.getStatus().name() : "PENDING",
                    "resultContent", result != null && result.getResultContent() != null
                            ? result.getResultContent() : "",
                    "failureReason", result != null && result.getFailureReason() != null
                            ? result.getFailureReason() : "",
                    "steps", steps
            );
        }).collect(Collectors.toList());

        return ResponseEntity.ok(trace);
    }
}
