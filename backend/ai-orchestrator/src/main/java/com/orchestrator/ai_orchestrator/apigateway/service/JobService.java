package com.orchestrator.ai_orchestrator.apigateway.service;

import com.orchestrator.ai_orchestrator.apigateway.domain.Job;
import com.orchestrator.ai_orchestrator.apigateway.domain.JobStatus;
import com.orchestrator.ai_orchestrator.apigateway.infrastructure.JobRepository;
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

        Job job = Job.builder()
                .userId(userId)
                .goal(goal.trim())
                .status(JobStatus.RECEIVED)
                .finalAnswerId(null)
                .createdAt(LocalDateTime.now())
                .updatedAt(LocalDateTime.now())
                .build();

        Job savedJob = jobRepository.save(job);

        observerService.emit("API_GATEWAY", "JOB_RECEIVED", savedJob.getId(),
                Map.of("jobId", savedJob.getId().toString(), "goal", goal));

        Thread.ofVirtual().start(() -> runPipeline(savedJob.getId(), savedJob.getGoal()));

        return savedJob.getId();
    }

    public Optional<Job> getJob(UUID jobId) {
        return jobRepository.findById(jobId);
    }

    private void runPipeline(UUID jobId, String goal) {
        try {
            updateJobStatus(jobId, JobStatus.PLANNING);
            plannerService.plan(jobId, goal);

            updateJobStatus(jobId, JobStatus.ROUTING);
            routerService.route(jobId);

            updateJobStatus(jobId, JobStatus.EXECUTING);
            log.info("Pipeline handed off to Agent Executor for jobId={}", jobId);

        } catch (Exception e) {
            log.error("Pipeline failed for jobId={} error={}", jobId, e.getMessage());
            updateJobStatus(jobId, JobStatus.FAILED);
            observerService.emit("API_GATEWAY", "JOB_FAILED", jobId,
                    Map.of("jobId", jobId.toString(), "error", e.getMessage()));
        }
    }

    private void updateJobStatus(UUID jobId, JobStatus status) {
        jobRepository.findById(jobId).ifPresent(job -> {
            job.setStatus(status);
            job.setUpdatedAt(LocalDateTime.now());
            jobRepository.save(job);
        });
    }
}
