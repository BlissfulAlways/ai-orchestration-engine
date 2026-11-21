package com.orchestrator.ai_orchestrator.apigateway.service;

import com.orchestrator.ai_orchestrator.aggregator.service.AggregatorTriggerService;
import com.orchestrator.ai_orchestrator.apigateway.domain.Job;
import com.orchestrator.ai_orchestrator.apigateway.domain.JobStatus;
import com.orchestrator.ai_orchestrator.apigateway.infrastructure.JobRepository;
import com.orchestrator.ai_orchestrator.observer.service.ObserverService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Finds jobs that stopped making progress (typically because the application restarted in the
 * middle of their work) and gets them moving again.
 *
 * <pre>
 *   status that is stale   what recovery does
 *   RECEIVED/PLANNING/ROUTING  resume the pipeline (it skips work that is already done)
 *   EXECUTING                  re-check whether every task is settled; if yes, aggregate
 *   AGGREGATING                put the job back to EXECUTING and aggregate again
 * </pre>
 *
 * <p>Every step is safe to repeat: status changes are compare-and-set, the task result and
 * the final answer each have a uniqueness rule in the database, and aggregation can only be
 * claimed once.
 */
@Slf4j
@Service
public class JobRecoveryService {

    private static final List<JobStatus> RECOVERABLE_STATUSES = List.of(
            JobStatus.RECEIVED, JobStatus.PLANNING, JobStatus.ROUTING,
            JobStatus.EXECUTING, JobStatus.AGGREGATING);

    private final JobRepository jobRepository;
    private final JobService jobService;
    private final AggregatorTriggerService aggregatorTriggerService;
    private final ObserverService observerService;
    private final int staleMinutes;

    public JobRecoveryService(JobRepository jobRepository,
                              JobService jobService,
                              AggregatorTriggerService aggregatorTriggerService,
                              ObserverService observerService,
                              @Value("${orchestrator.recovery.stale-minutes:10}") int staleMinutes) {
        this.jobRepository = jobRepository;
        this.jobService = jobService;
        this.aggregatorTriggerService = aggregatorTriggerService;
        this.observerService = observerService;
        this.staleMinutes = staleMinutes;
    }

    @Scheduled(fixedDelayString = "${orchestrator.recovery.interval-ms:60000}")
    public void recoverStalledJobs() {
        Instant cutoff = Instant.now().minusSeconds(staleMinutes * 60L);
        for (Job job : jobRepository.findByStatusInAndUpdatedAtBefore(RECOVERABLE_STATUSES, cutoff)) {
            try {
                recover(job);
            } catch (Exception e) {
                // One bad job must not stop recovery of the others.
                log.error("Recovery failed jobId={}", job.getId(), e);
            }
        }
    }

    private void recover(Job job) {
        UUID jobId = job.getId();
        JobStatus status = job.getStatus();

        boolean earlyStage = status == JobStatus.RECEIVED
                || status == JobStatus.PLANNING
                || status == JobStatus.ROUTING;
        if (earlyStage && jobService.isPipelineActive(jobId)) {
            return; // Slow, but still running inside this process.
        }

        // Claim the job. If another recoverer got here first, this returns 0 and we skip.
        if (jobRepository.claimForRecovery(jobId, status, job.getUpdatedAt(), Instant.now()) == 0) {
            return;
        }

        log.warn("Recovering stalled job jobId={} status={}", jobId, status);
        observerService.emit("JOB_RECOVERY", "JOB_RECOVERY_STARTED", jobId,
                Map.of("jobId", jobId.toString(), "status", status.name()));

        switch (status) {
            case RECEIVED, PLANNING, ROUTING -> jobService.resumePipeline(jobId);
            case EXECUTING -> aggregatorTriggerService.checkAndTrigger(jobId);
            case AGGREGATING -> {
                int reset = jobRepository.transitionStatus(
                        jobId, JobStatus.AGGREGATING, JobStatus.EXECUTING, Instant.now());
                if (reset == 1) {
                    aggregatorTriggerService.checkAndTrigger(jobId);
                }
            }
            default -> { /* Final states are never selected for recovery. */ }
        }
    }
}