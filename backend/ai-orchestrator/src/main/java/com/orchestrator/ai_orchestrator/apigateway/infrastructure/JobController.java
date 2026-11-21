package com.orchestrator.ai_orchestrator.apigateway.infrastructure;

import com.orchestrator.ai_orchestrator.apigateway.domain.Job;
import com.orchestrator.ai_orchestrator.apigateway.service.JobQueryService;
import com.orchestrator.ai_orchestrator.apigateway.service.JobService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

/** HTTP layer only: validates input, calls a service, shapes the response. */
@Slf4j
@RestController
@RequestMapping("/api/jobs")
@RequiredArgsConstructor
public class JobController {

    private final JobService jobService;
    private final JobQueryService jobQueryService;

    public record SubmitJobRequest(
            @NotBlank(message = "userId cannot be blank") String userId,
            @NotBlank(message = "goal cannot be blank") String goal
    ) {}

    public record JobResponse(
            UUID jobId,
            String status,
            String goal,
            String finalAnswer,
            String failureReason,
            Instant createdAt,
            Instant updatedAt
    ) {
        static JobResponse from(JobQueryService.JobView view) {
            Job job = view.job();
            return new JobResponse(
                    job.getId(),
                    job.getStatus().name(),
                    job.getGoal(),
                    view.finalAnswer(),
                    job.getFailureReason(),
                    job.getCreatedAt(),
                    job.getUpdatedAt());
        }
    }

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
        return jobQueryService.findJob(jobId)
                .map(JobResponse::from)
                .map(ResponseEntity::ok)
                .orElseGet(() -> ResponseEntity.notFound().build());
    }
}