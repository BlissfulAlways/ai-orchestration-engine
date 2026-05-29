package com.orchestrator.ai_orchestrator.aggregator.service;

import com.orchestrator.ai_orchestrator.aggregator.domain.FinalAnswer;
import com.orchestrator.ai_orchestrator.aggregator.infrastructure.FinalAnswerRepository;
import com.orchestrator.ai_orchestrator.apigateway.domain.Job;
import com.orchestrator.ai_orchestrator.apigateway.domain.JobStatus;
import com.orchestrator.ai_orchestrator.apigateway.infrastructure.JobRepository;
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
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Collectors;

@Slf4j
@Service
@RequiredArgsConstructor
public class AggregatorService {

    private final TaskResultRepository taskResultRepository;
    private final FinalAnswerRepository finalAnswerRepository;
    private final JobRepository jobRepository;
    private final LlmGatewayService llmGatewayService;
    private final ObserverService observerService;

    public void aggregate(UUID jobId) {
        try {
            observerService.emit("RESULT_AGGREGATOR", "AGGREGATION_STARTED", jobId,
                    Map.of("jobId", jobId.toString()));

            Optional<Job> jobOpt = jobRepository.findById(jobId);
            if (jobOpt.isEmpty()) {
                log.error("Job not found for aggregation jobId={}", jobId);
                return;
            }

            Job job = jobOpt.get();
            job.setStatus(JobStatus.AGGREGATING);
            job.setUpdatedAt(LocalDateTime.now());
            jobRepository.save(job);

            List<TaskResult> results = taskResultRepository.findByJobId(jobId);

            String combinedResults = results.stream()
                    .filter(r -> r.getResultContent() != null)
                    .map(r -> "- " + r.getResultContent())
                    .collect(Collectors.joining("\n"));

            String systemPrompt = """
                    You are a result synthesis AI.
                    You will receive a goal and a list of sub-task results.
                    Synthesize them into one clear, coherent, complete final answer.
                    Respond with only the final answer. No preamble. No explanation.
                    """;

            String userPrompt = "Goal: " + job.getGoal() + "\n\nSub-task results:\n" + combinedResults;

            String finalAnswerText = llmGatewayService.call("RESULT_AGGREGATOR", systemPrompt, userPrompt);

            FinalAnswer finalAnswer = FinalAnswer.builder()
                    .jobId(jobId)
                    .answerContent(finalAnswerText)
                    .createdAt(LocalDateTime.now())
                    .build();

            FinalAnswer savedAnswer = finalAnswerRepository.save(finalAnswer);

            job.setStatus(JobStatus.COMPLETED);
            job.setFinalAnswerId(savedAnswer.getId());
            job.setUpdatedAt(LocalDateTime.now());
            jobRepository.save(job);

            observerService.emit("RESULT_AGGREGATOR", "AGGREGATION_COMPLETED", jobId,
                    Map.of("jobId", jobId.toString(), "finalAnswerId", savedAnswer.getId().toString()));

        } catch (Exception e) {
            log.error("AggregatorService failed for jobId={} error={}", jobId, e.getMessage());

            jobRepository.findById(jobId).ifPresent(job -> {
                job.setStatus(JobStatus.FAILED);
                job.setUpdatedAt(LocalDateTime.now());
                jobRepository.save(job);
            });

            observerService.emit("RESULT_AGGREGATOR", "AGGREGATION_FAILED", jobId,
                    Map.of("jobId", jobId.toString(), "error", e.getMessage()));
        }
    }
}
