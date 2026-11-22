package com.orchestrator.ai_orchestrator.apigateway.service;

import com.orchestrator.ai_orchestrator.apigateway.domain.JobStatus;
import com.orchestrator.ai_orchestrator.apigateway.infrastructure.JobRepository;
import com.orchestrator.ai_orchestrator.planner.domain.PlannedTask;
import com.orchestrator.ai_orchestrator.planner.infrastructure.PlannedTaskRepository;
import com.orchestrator.ai_orchestrator.taskqueue.domain.QueuedTask;
import com.orchestrator.ai_orchestrator.taskqueue.domain.QueuedTaskStatus;
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
 * Answers "how far along is my job?" with a few counts, cheap enough to be polled every second.
 *
 * <p>The total comes from the PLAN (known as soon as planning finishes), and the running,
 * completed and failed counts come from the queue. Whatever is in none of those states is
 * waiting, so the numbers always add up to the total.
 */
@Service
@RequiredArgsConstructor
public class JobProgressService {

    private final JobRepository jobRepository;
    private final PlannedTaskRepository plannedTaskRepository;
    private final TaskQueueRepository taskQueueRepository;

    /**
     * @param jobStatus              the job's own status, e.g. EXECUTING
     * @param totalTasks             tasks in the plan (0 until planning has finished)
     * @param percentComplete        0 to 100; 100 once the job has finished successfully
     * @param runningTaskDescriptions what the agents are working on right now
     */
    public record Progress(
            String jobStatus,
            int totalTasks,
            int pending,
            int running,
            int completed,
            int failed,
            int percentComplete,
            List<String> runningTaskDescriptions) {}

    /** @return empty if the job does not exist or belongs to someone else */
    @Transactional(readOnly = true)
    public Optional<Progress> getProgress(UUID jobId, String userId) {
        return jobRepository.findByIdAndUserId(jobId, userId).map(job -> {
            List<PlannedTask> plan = plannedTaskRepository.findByJobIdOrderBySequenceNumberAsc(jobId);
            Map<UUID, PlannedTask> planById = plan.stream()
                    .collect(Collectors.toMap(PlannedTask::getId, Function.identity()));
            List<QueuedTask> queue = taskQueueRepository.findByJobId(jobId);

            int total = plan.size();
            int running = count(queue, QueuedTaskStatus.PROCESSING);
            int completed = count(queue, QueuedTaskStatus.COMPLETED);
            int failed = count(queue, QueuedTaskStatus.FAILED);
            int pending = Math.max(0, total - running - completed - failed);

            List<String> runningDescriptions = queue.stream()
                    .filter(task -> task.getStatus() == QueuedTaskStatus.PROCESSING)
                    .map(task -> planById.get(task.getPlannedTaskId()))
                    .filter(planned -> planned != null)
                    .map(PlannedTask::getTaskDescription)
                    .toList();

            return new Progress(job.getStatus().name(), total, pending, running, completed, failed,
                    percent(job.getStatus(), total, completed, failed), runningDescriptions);
        });
    }

    private static int count(List<QueuedTask> queue, QueuedTaskStatus status) {
        return (int) queue.stream().filter(task -> task.getStatus() == status).count();
    }

    private static int percent(JobStatus status, int total, int completed, int failed) {
        if (status == JobStatus.COMPLETED || status == JobStatus.COMPLETED_WITH_ERRORS) {
            return 100;
        }
        if (total == 0) {
            return 0;
        }
        // The last bit (writing the final answer) is only counted once the job is done,
        // so a bar never sits at 100 while the job is still working.
        return Math.min(99, (completed + failed) * 100 / total);
    }
}