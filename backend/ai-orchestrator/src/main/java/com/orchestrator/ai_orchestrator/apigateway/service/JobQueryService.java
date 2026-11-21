package com.orchestrator.ai_orchestrator.apigateway.service;

import com.orchestrator.ai_orchestrator.aggregator.domain.FinalAnswer;
import com.orchestrator.ai_orchestrator.aggregator.infrastructure.FinalAnswerRepository;
import com.orchestrator.ai_orchestrator.apigateway.domain.Job;
import com.orchestrator.ai_orchestrator.apigateway.infrastructure.JobRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Optional;
import java.util.UUID;

/**
 * Read side of the job API: "what is the state of this job?".
 *
 * <p>Kept separate from {@link JobService}, which drives the pipeline. The controller used to
 * query the final-answer table itself; that logic belongs in a service so controllers only
 * translate HTTP to method calls.
 */
@Service
@RequiredArgsConstructor
public class JobQueryService {

    private final JobRepository jobRepository;
    private final FinalAnswerRepository finalAnswerRepository;

    /** A job together with its final answer text (null until the job has an answer). */
    public record JobView(Job job, String finalAnswer) {}

    /** readOnly = true: no writes happen here, and both reads see one consistent snapshot. */
    @Transactional(readOnly = true)
    public Optional<JobView> findJob(UUID jobId) {
        return jobRepository.findById(jobId)
                .map(job -> new JobView(job, loadFinalAnswer(job)));
    }

    /**
     * A final answer is linked to the job only at the moment the job reaches COMPLETED or
     * COMPLETED_WITH_ERRORS, so "has a link" is the right test, not a status comparison.
     */
    private String loadFinalAnswer(Job job) {
        if (job.getFinalAnswerId() == null) {
            return null;
        }
        return finalAnswerRepository.findById(job.getFinalAnswerId())
                .map(FinalAnswer::getAnswerContent)
                .orElse(null);
    }
}