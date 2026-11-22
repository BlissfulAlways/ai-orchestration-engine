package com.orchestrator.ai_orchestrator.apigateway.infrastructure;

import com.orchestrator.ai_orchestrator.apigateway.domain.Job;
import com.orchestrator.ai_orchestrator.apigateway.service.JobQueryService;
import com.orchestrator.ai_orchestrator.apigateway.service.JobService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.security.Principal;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * HTTP layer only: validates input, calls a service, shapes the response.
 *
 * <p>Version 1 of the API. {@code principal.getName()} is the owner's user id, taken from the
 * API key by the security filter; it is never read from the request body.
 */
@Slf4j
@RestController
@RequestMapping("/api/v1/jobs")
@RequiredArgsConstructor
public class JobController {

    private static final int MAX_PAGE_SIZE = 100;

    private final JobService jobService;
    private final JobQueryService jobQueryService;

    public record SubmitJobRequest(
            @NotBlank(message = "goal cannot be blank")
            @Size(max = 4000, message = "goal must be at most 4000 characters")
            String goal
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

    /** A job in a list: no answer text, so a page of jobs stays small. */
    public record JobSummary(UUID jobId, String status, String goal, Instant createdAt, Instant updatedAt) {
        static JobSummary from(Job job) {
            return new JobSummary(job.getId(), job.getStatus().name(), job.getGoal(),
                    job.getCreatedAt(), job.getUpdatedAt());
        }
    }

    @PostMapping
    public ResponseEntity<Map<String, String>> submitJob(@Valid @RequestBody SubmitJobRequest request,
                                                         Principal principal) {
        UUID jobId = jobService.submitJob(principal.getName(), request.goal());
        log.info("Job submitted jobId={} userId={}", jobId, principal.getName());
        return ResponseEntity.accepted().body(Map.of(
                "jobId", jobId.toString(),
                "message", "Job submitted successfully"
        ));
    }

    @GetMapping
    public List<JobSummary> listJobs(@RequestParam(defaultValue = "0") int page,
                                     @RequestParam(defaultValue = "20") int size,
                                     Principal principal) {
        // Clamped instead of rejected: a bad value gives the nearest valid page, never an error.
        int safePage = Math.max(0, page);
        int safeSize = Math.min(MAX_PAGE_SIZE, Math.max(1, size));
        return jobQueryService.listJobs(principal.getName(), safePage, safeSize).stream()
                .map(JobSummary::from)
                .toList();
    }

    @GetMapping("/{jobId}")
    public ResponseEntity<JobResponse> getJob(@PathVariable UUID jobId, Principal principal) {
        return jobQueryService.findJob(jobId, principal.getName())
                .map(JobResponse::from)
                .map(ResponseEntity::ok)
                .orElseGet(() -> ResponseEntity.notFound().build());
    }
}