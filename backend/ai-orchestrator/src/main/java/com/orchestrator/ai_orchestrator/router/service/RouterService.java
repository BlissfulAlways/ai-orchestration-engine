package com.orchestrator.ai_orchestrator.router.service;

import com.orchestrator.ai_orchestrator.observer.service.ObserverService;
import com.orchestrator.ai_orchestrator.planner.domain.PlannedTask;
import com.orchestrator.ai_orchestrator.planner.infrastructure.PlannedTaskRepository;
import com.orchestrator.ai_orchestrator.router.infrastructure.AgentRegistryRepository;
import com.orchestrator.ai_orchestrator.taskqueue.domain.QueuedTask;
import com.orchestrator.ai_orchestrator.taskqueue.domain.QueuedTaskStatus;
import com.orchestrator.ai_orchestrator.taskqueue.infrastructure.TaskQueueRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Slf4j
@Service
@RequiredArgsConstructor
public class RouterService {

    private final PlannedTaskRepository plannedTaskRepository;
    private final AgentRegistryRepository agentRegistryRepository;
    private final TaskQueueRepository taskQueueRepository;
    private final ObserverService observerService;

    public void route(UUID jobId) {
        try {
            observerService.emit("TASK_ROUTER", "ROUTING_STARTED", jobId,
                    Map.of("jobId", jobId.toString()));

            List<PlannedTask> plannedTasks = plannedTaskRepository
                    .findByJobIdOrderBySequenceNumberAsc(jobId);

            int queuedCount = 0;

            for (PlannedTask task : plannedTasks) {
                boolean agentExists = agentRegistryRepository
                        .findActiveAgentForTaskType(task.getRequiredAgentType())
                        .isPresent();

                if (!agentExists) {
                    log.warn("No active agent found for taskType={} jobId={}",
                            task.getRequiredAgentType(), jobId);
                    observerService.emit("TASK_ROUTER", "NO_AGENT_FOR_TASK", jobId,
                            Map.of("taskId", task.getId().toString(),
                                    "requiredAgentType", task.getRequiredAgentType()));
                    continue;
                }

                QueuedTask queuedTask = QueuedTask.builder()
                        .jobId(jobId)
                        .plannedTaskId(task.getId())
                        .requiredAgentType(task.getRequiredAgentType())
                        .status(QueuedTaskStatus.PENDING)
                        .retryCount(0)
                        .queuedAt(LocalDateTime.now())
                        .pickedUpAt(null)
                        .build();

                taskQueueRepository.save(queuedTask);
                queuedCount++;
            }

            observerService.emit("TASK_ROUTER", "ROUTING_COMPLETED", jobId,
                    Map.of("jobId", jobId.toString(), "queuedTaskCount", queuedCount));

        } catch (Exception e) {
            log.error("RouterService failed for jobId={} error={}", jobId, e.getMessage());
            observerService.emit("TASK_ROUTER", "ROUTING_FAILED", jobId,
                    Map.of("jobId", jobId.toString(), "error", e.getMessage()));
            throw new RuntimeException("Routing failed for jobId: " + jobId, e);
        }
    }
}
