package com.orchestrator.ai_orchestrator.apigateway.infrastructure;

import com.orchestrator.ai_orchestrator.apigateway.service.JobProgressService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.security.Principal;
import java.util.UUID;

/** HTTP layer only. A job owned by someone else answers 404, like one that does not exist. */
@RestController
@RequestMapping("/api/v1/jobs")
@RequiredArgsConstructor
public class JobProgressController {

    private final JobProgressService jobProgressService;

    @GetMapping("/{jobId}/progress")
    public ResponseEntity<JobProgressService.Progress> getProgress(@PathVariable UUID jobId,
                                                                   Principal principal) {
        return jobProgressService.getProgress(jobId, principal.getName())
                .map(ResponseEntity::ok)
                .orElseGet(() -> ResponseEntity.notFound().build());
    }
}