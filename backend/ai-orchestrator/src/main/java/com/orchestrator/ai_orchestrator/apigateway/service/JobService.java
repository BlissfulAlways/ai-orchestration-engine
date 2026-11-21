package com.orchestrator.ai_orchestrator.apigateway.service;

import com.orchestrator.ai_orchestrator.apigateway.domain.Job;
import com.orchestrator.ai_orchestrator.apigateway.domain.JobStatus;
import com.orchestrator.ai_orchestrator.apigateway.infrastructure.JobRepository;
import com.orchestrator.ai_orchestrator.common.ErrorMessages;
import com.orchestrator.ai_orchestrator.observer.service.ObserverService;
import com.orchestrator.ai_orchestrator.planner.service.PlanLookupService;
import com.orchestrator.ai_orchestrator.planner.service.PlannerService;
import com.orchestrator.ai_orchestrator.router.service.RouterService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Accepts jobs and drives them through the early pipeline: RECEIVED -> PLANNING -> ROUTING.
 * The router moves the job to EXECUTING; the executor and aggregator take it from there.
 *
 * <p>The pipeline is RESUMABLE. It decides what to do from the job's current status and from
 * whether a plan already exists, so it can be started again for a job that was interrupted
 * (for example by an application restart) without repeating finished work:
 * <ul>
 *   <li>RECEIVED: plan, then route</li>
 *   <li>PLANNING: plan only if no plan was saved yet, then route</li>
 *   <li>ROUTING:  route (routing is all-or-nothing, so a retry is safe)</li>
 * </ul>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class JobService {

    private final JobRepository jobRepository;
    private final PlannerService plannerService;
    private final PlanLookupService planLookupService;
    private final RouterService routerService;
    private final ObserverService observerService;

    /**
     * Jobs whose pipeline is running inside this process right now. Lets the recovery service
     * tell "slow but alive" apart from "abandoned".
     */
    private final Set<UUID> activePipelines = ConcurrentHashMap.newKeySet();

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

        // The caller gets its jobId immediately; planning and routing continue in the background.
        startPipeline(savedJob.getId());
        return savedJob.getId();
    }

    public Optional<Job> getJob(UUID jobId) {
        return jobRepository.findById(jobId);
    }

    /** Starts (or restarts) the pipeline for a job that was interrupted. Safe to call twice. */
    public void resumePipeline(UUID jobId) {
        startPipeline(jobId);
    }

    /** True while this process is running the pipeline for the job. */
    public boolean isPipelineActive(UUID jobId) {
        return activePipelines.contains(jobId);
    }

    private void startPipeline(UUID jobId) {
        // The job is registered BEFORE the thread starts, so there is no moment in which the
        // recovery service could see it as inactive.
        if (!activePipelines.add(jobId)) {
            log.info("Pipeline already running jobId={}", jobId);
            return;
        }
        try {
            Thread.ofVirtual()
                    .name("job-pipeline-" + jobId)
                    .start(() -> runPipeline(jobId));
        } catch (RuntimeException e) {
            activePipelines.remove(jobId);
            throw e;
        }
    }

    private void runPipeline(UUID jobId) {
        try {
            Job job = jobRepository.findById(jobId)
                    .orElseThrow(() -> new IllegalStateException("Job not found: " + jobId));
            JobStatus status = job.getStatus();

            if (status == JobStatus.RECEIVED) {
                advance(jobId, JobStatus.RECEIVED, JobStatus.PLANNING);
                status = JobStatus.PLANNING;
            }
            if (status == JobStatus.PLANNING) {
                if (!planLookupService.hasPlan(jobId)) {
                    plannerService.plan(jobId, job.getGoal());
                }
                advance(jobId, JobStatus.PLANNING, JobStatus.ROUTING);
                status = JobStatus.ROUTING;
            }
            if (status == JobStatus.ROUTING) {
                routerService.route(jobId); // also moves the job to EXECUTING
                log.info("Pipeline handed off to Agent Executor jobId={}", jobId);
            } else {
                log.info("Nothing to resume jobId={} status={}", jobId, status);
            }
        } catch (Exception e) {
            log.error("Pipeline failed jobId={}", jobId, e);
            failJob(jobId, e);
        } finally {
            activePipelines.remove(jobId);
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