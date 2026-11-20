package com.orchestrator.ai_orchestrator.apigateway.service;

import com.orchestrator.ai_orchestrator.apigateway.domain.Job;
import com.orchestrator.ai_orchestrator.apigateway.domain.JobStatus;
import com.orchestrator.ai_orchestrator.apigateway.infrastructure.JobRepository;
import com.orchestrator.ai_orchestrator.common.ErrorMessages;
import com.orchestrator.ai_orchestrator.observer.service.ObserverService;
import com.orchestrator.ai_orchestrator.planner.service.PlannerService;
import com.orchestrator.ai_orchestrator.router.service.RouterService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Accepts jobs and drives them through the early pipeline: RECEIVED -> PLANNING -> ROUTING.
 * The router moves the job to EXECUTING; the executor and aggregator take it from there.
 *
 * <p>Every status change is a compare-and-set (see {@link JobRepository}). If the job is not in
 * the expected state, the change is refused instead of silently overwriting newer progress.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class JobService {

    private final JobRepository jobRepository;
    private final PlannerService plannerService;
    private final RouterService routerService;
    private final ObserverService observerService;

    public UUID submitJob(String userId, String goal) {
        if (goal == null || goal.trim().isEmpty()) {
            throw new IllegalArgumentException("Goal cannot be empty");
        }

        LocalDateTime now = LocalDateTime.now();
        Job savedJob = jobRepository.save(Job.builder()
                .userId(userId)
                .goal(goal.trim())
                .status(JobStatus.RECEIVED)
                .finalAnswerId(null)
                .failureReason(null)
                .createdAt(now)
                .updatedAt(now)
                .build());

        observerService.emit("API_GATEWAY", "JOB_RECEIVED", savedJob.getId(),
                Map.of("jobId", savedJob.getId().toString(), "goal", savedJob.getGoal()));

        UUID jobId = savedJob.getId();
        String trimmedGoal = savedJob.getGoal();
        // The caller gets its jobId immediately; planning and routing continue in the background.
        Thread.ofVirtual()
                .name("job-pipeline-" + jobId)
                .start(() -> runPipeline(jobId, trimmedGoal));

        return jobId;
    }

    public Optional<Job> getJob(UUID jobId) {
        return jobRepository.findById(jobId);
    }

    private void runPipeline(UUID jobId, String goal) {
        try {
            advance(jobId, JobStatus.RECEIVED, JobStatus.PLANNING);
            plannerService.plan(jobId, goal);

            advance(jobId, JobStatus.PLANNING, JobStatus.ROUTING);
            routerService.route(jobId); // also moves the job to EXECUTING

            log.info("Pipeline handed off to Agent Executor jobId={}", jobId);
        } catch (Exception e) {
            log.error("Pipeline failed jobId={}", jobId, e);
            failJob(jobId, e);
        }
    }

    /** Changes status only if the job is currently in {@code from}; otherwise throws. */
    private void advance(UUID jobId, JobStatus from, JobStatus to) {
        int updated = jobRepository.transitionStatus(jobId, from, to, LocalDateTime.now());
        if (updated == 0) {
            throw new IllegalStateException(
                    "Job " + jobId + " was expected in " + from + " but was not; refusing to move to " + to);
        }
    }

    private void failJob(UUID jobId, Exception error) {
        String reason = ErrorMessages.describe(error);
        try {
            jobRepository.markFailed(jobId, reason, LocalDateTime.now());
            observerService.emit("API_GATEWAY", "JOB_FAILED", jobId,
                    Map.of("jobId", jobId.toString(), "error", reason));
        } catch (Exception secondary) {
            log.error("Could not mark job FAILED jobId={}", jobId, secondary);
        }
    }
}