package com.orchestrator.ai_orchestrator.apigateway.infrastructure;

import com.orchestrator.ai_orchestrator.apigateway.service.JobTraceService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

/** HTTP layer only. All trace logic lives in {@link JobTraceService}. */
@RestController
@RequestMapping("/api/jobs")
@RequiredArgsConstructor
public class JobTraceController {

    private final JobTraceService jobTraceService;

    @GetMapping("/{jobId}/trace")
    public ResponseEntity<List<JobTraceService.TaskTrace>> getJobTrace(@PathVariable UUID jobId) {
        return jobTraceService.getTrace(jobId)
                .map(ResponseEntity::ok)
                .orElseGet(() -> ResponseEntity.notFound().build());
    }
}