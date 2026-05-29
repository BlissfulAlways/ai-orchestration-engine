package com.orchestrator.ai_orchestrator.apigateway.infrastructure;

import com.orchestrator.ai_orchestrator.aggregator.infrastructure.FinalAnswerRepository;
import com.orchestrator.ai_orchestrator.apigateway.domain.Job;
import com.orchestrator.ai_orchestrator.apigateway.domain.JobStatus;
import com.orchestrator.ai_orchestrator.apigateway.service.JobService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDateTime;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

@Slf4j
@RestController
@RequestMapping("/api/jobs")
@RequiredArgsConstructor
public class JobController {

    private final JobService jobService;
    private final FinalAnswerRepository finalAnswerRepository;

    public record SubmitJobRequest(
            @NotBlank(message = "userId cannot be blank") String userId,
            @NotBlank(message = "goal cannot be blank") String goal
    ) {}

    public record JobResponse(
            UUID jobId,
            String status,
            String goal,
            String finalAnswer,
            LocalDateTime createdAt,
            LocalDateTime updatedAt
    ) {}

    @PostMapping
    public ResponseEntity<Map<String, String>> submitJob(@Valid @RequestBody SubmitJobRequest request) {
        UUID jobId = jobService.submitJob(request.userId(), request.goal());
        log.info("Job submitted jobId={} userId={}", jobId, request.userId());
        return ResponseEntity.accepted().body(Map.of(
                "jobId", jobId.toString(),
                "message", "Job submitted successfully"
        ));
    }

    @GetMapping("/{jobId}")
    public ResponseEntity<JobResponse> getJob(@PathVariable UUID jobId) {
        Optional<Job> jobOpt = jobService.getJob(jobId);

        if (jobOpt.isEmpty()) {
            return ResponseEntity.notFound().build();
        }

        Job job = jobOpt.get();

        String finalAnswerText = null;
        if (job.getStatus() == JobStatus.COMPLETED && job.getFinalAnswerId() != null) {
            finalAnswerText = finalAnswerRepository
                    .findById(job.getFinalAnswerId())
                    .map(fa -> fa.getAnswerContent())
                    .orElse(null);
        }

        JobResponse response = new JobResponse(
                job.getId(),
                job.getStatus().name(),
                job.getGoal(),
                finalAnswerText,
                job.getCreatedAt(),
                job.getUpdatedAt()
        );

        return ResponseEntity.ok(response);
    }
}
