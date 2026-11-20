package com.orchestrator.ai_orchestrator.router.service;

import com.orchestrator.ai_orchestrator.apigateway.domain.JobStatus;
import com.orchestrator.ai_orchestrator.apigateway.infrastructure.JobRepository;
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
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Turns planned tasks into queue entries.
 *
 * <p>All-or-nothing: if any task has no agent that can run it, NOTHING is queued and the
 * method throws. Previously such tasks were skipped silently, so the job's task count could
 * never be reached and the job hung forever.
 *
 * <p>The job moves ROUTING -> EXECUTING inside the same transaction that queues the tasks.
 * The executor can only see the tasks after commit, so by then the job is already EXECUTING.
 * That ordering removes the race where a fast task finished before the status was updated.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class RouterService {

    private final PlannedTaskRepository plannedTaskRepository;
    private final AgentRegistryRepository agentRegistryRepository;
    private final TaskQueueRepository taskQueueRepository;
    private final JobRepository jobRepository;
    private final ObserverService observerService;

    // Note: a ROUTING_FAILED event is intentionally not emitted here. This method runs inside a
    // transaction that rolls back on failure, which would roll back the event too. The failure
    // is recorded outside the transaction by JobService.failJob (event JOB_FAILED + reason).
    @Transactional
    public void route(UUID jobId) {
        observerService.emit("TASK_ROUTER", "ROUTING_STARTED", jobId,
                Map.of("jobId", jobId.toString()));

        List<PlannedTask> plannedTasks = plannedTaskRepository
                .findByJobIdOrderBySequenceNumberAsc(jobId);

        if (plannedTasks.isEmpty()) {
            throw new IllegalStateException("Planner produced no tasks for job " + jobId);
        }

        // Validate first, queue second, so a bad plan leaves nothing half-queued.
        List<String> unroutableTypes = plannedTasks.stream()
                .map(PlannedTask::getRequiredAgentType)
                .distinct()
                .filter(type -> agentRegistryRepository.findActiveAgentForTaskType(type).isEmpty())
                .toList();
        if (!unroutableTypes.isEmpty()) {
            throw new IllegalStateException(
                    "No active agent registered for task type(s): " + unroutableTypes);
        }

        LocalDateTime now = LocalDateTime.now();
        for (PlannedTask task : plannedTasks) {
            taskQueueRepository.save(QueuedTask.builder()
                    .jobId(jobId)
                    .plannedTaskId(task.getId())
                    .requiredAgentType(task.getRequiredAgentType())
                    .status(QueuedTaskStatus.PENDING)
                    .retryCount(0)
                    .queuedAt(now)
                    .pickedUpAt(null)
                    .build());
        }

        int moved = jobRepository.transitionStatus(
                jobId, JobStatus.ROUTING, JobStatus.EXECUTING, now);
        if (moved == 0) {
            // Throwing rolls back the queue entries above.
            throw new IllegalStateException("Job " + jobId + " was not in ROUTING state");
        }

        observerService.emit("TASK_ROUTER", "ROUTING_COMPLETED", jobId,
                Map.of("jobId", jobId.toString(), "queuedTaskCount", plannedTasks.size()));
    }
}