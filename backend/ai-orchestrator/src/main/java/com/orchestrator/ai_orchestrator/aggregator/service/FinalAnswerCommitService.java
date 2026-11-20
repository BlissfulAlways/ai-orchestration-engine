package com.orchestrator.ai_orchestrator.aggregator.service;

import com.orchestrator.ai_orchestrator.aggregator.domain.FinalAnswer;
import com.orchestrator.ai_orchestrator.aggregator.infrastructure.FinalAnswerRepository;
import com.orchestrator.ai_orchestrator.apigateway.domain.JobStatus;
import com.orchestrator.ai_orchestrator.apigateway.infrastructure.JobRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.UUID;

/**
 * Saves the final answer and marks the job COMPLETED as ONE transaction.
 *
 * <p>Either both happen or neither does. If the job is no longer AGGREGATING, the status update
 * changes zero rows, we throw, and the transaction rolls back the answer we just inserted. So
 * there is never an orphan answer, and never a COMPLETED job without an answer.
 */
@Service
@RequiredArgsConstructor
public class FinalAnswerCommitService {

    private final FinalAnswerRepository finalAnswerRepository;
    private final JobRepository jobRepository;

    @Transactional
    public UUID commit(UUID jobId, String answerText) {
        LocalDateTime now = LocalDateTime.now();

        // saveAndFlush sends the INSERT immediately, so the foreign key from
        // jobs.final_answer_id finds the row when the UPDATE below runs.
        FinalAnswer saved = finalAnswerRepository.saveAndFlush(FinalAnswer.builder()
                .jobId(jobId)
                .answerContent(answerText)
                .createdAt(now)
                .build());

        int updated = jobRepository.completeJob(
                jobId, saved.getId(), JobStatus.AGGREGATING, JobStatus.COMPLETED, now);
        if (updated != 1) {
            throw new IllegalStateException(
                    "Job " + jobId + " was not in AGGREGATING state; discarding final answer");
        }
        return saved.getId();
    }
}