package com.orchestrator.ai_orchestrator.aggregator.service;

import com.orchestrator.ai_orchestrator.apigateway.domain.Job;
import com.orchestrator.ai_orchestrator.apigateway.domain.JobStatus;
import com.orchestrator.ai_orchestrator.apigateway.infrastructure.JobRepository;
import com.orchestrator.ai_orchestrator.common.ErrorMessages;
import com.orchestrator.ai_orchestrator.llmgateway.service.LlmGatewayService;
import com.orchestrator.ai_orchestrator.observer.service.ObserverService;
import com.orchestrator.ai_orchestrator.resultstore.domain.TaskResult;
import com.orchestrator.ai_orchestrator.resultstore.infrastructure.TaskResultRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * Combines all task results into the job's final answer.
 *
 * <p>Safe to call from several threads for the same job: the first caller wins the
 * EXECUTING -> AGGREGATING claim and does the work; every other caller returns immediately.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AggregatorService {

    private static final String SYSTEM_PROMPT = """
            You are a result synthesis AI.
            You will receive a goal and a list of sub-task results.
            Synthesize them into one clear, coherent, complete final answer.
            Respond with only the final answer. No preamble. No explanation.
            """;

    private final TaskResultRepository taskResultRepository;
    private final JobRepository jobRepository;
    private final LlmGatewayService llmGatewayService;
    private final ObserverService observerService;
    private final FinalAnswerCommitService finalAnswerCommitService;

    public void aggregate(UUID jobId) {
        // Step 1: claim the job. The status check happens inside the UPDATE statement, so
        // exactly one caller gets 1 back. This replaces a read-then-write, which two threads
        // could both pass.
        int claimed = jobRepository.transitionStatus(
                jobId, JobStatus.EXECUTING, JobStatus.AGGREGATING, LocalDateTime.now());
        if (claimed == 0) {
            log.info("Aggregation skipped jobId={}: not in EXECUTING (already claimed or finished)", jobId);
            return;
        }

        try {
            observerService.emit("RESULT_AGGREGATOR", "AGGREGATION_STARTED", jobId,
                    Map.of("jobId", jobId.toString()));

            Job job = jobRepository.findById(jobId)
                    .orElseThrow(() -> new IllegalStateException("Job disappeared during aggregation: " + jobId));

            List<TaskResult> results = taskResultRepository.findByJobId(jobId);
            String combinedResults = results.stream()
                    .filter(r -> r.getResultContent() != null)
                    .map(r -> "- " + r.getResultContent())
                    .collect(Collectors.joining("\n"));

            String userPrompt = "Goal: " + job.getGoal() + "\n\nSub-task results:\n" + combinedResults;

            // Step 2: the slow LLM call runs with NO transaction open and no connection held.
            String finalAnswerText = llmGatewayService.call("RESULT_AGGREGATOR", SYSTEM_PROMPT, userPrompt);

            // Step 3: save answer + mark COMPLETED atomically.
            UUID finalAnswerId = finalAnswerCommitService.commit(jobId, finalAnswerText);

            observerService.emit("RESULT_AGGREGATOR", "AGGREGATION_COMPLETED", jobId,
                    Map.of("jobId", jobId.toString(), "finalAnswerId", finalAnswerId.toString()));
        } catch (Exception e) {
            log.error("AggregatorService failed jobId={}", jobId, e);
            failJob(jobId, e);
        }
    }

    private void failJob(UUID jobId, Exception error) {
        String reason = ErrorMessages.describe(error);
        try {
            jobRepository.markFailed(jobId, reason, LocalDateTime.now());
            observerService.emit("RESULT_AGGREGATOR", "AGGREGATION_FAILED", jobId,
                    Map.of("jobId", jobId.toString(), "error", reason));
        } catch (Exception secondary) {
            log.error("Could not mark job FAILED jobId={}", jobId, secondary);
        }
    }
}