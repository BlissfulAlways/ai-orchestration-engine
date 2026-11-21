package com.orchestrator.ai_orchestrator.planner.service;

import com.orchestrator.ai_orchestrator.planner.infrastructure.PlannedTaskRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.UUID;

/** Read-only questions about a job's plan. Lets other modules ask without touching planner storage. */
@Service
@RequiredArgsConstructor
public class PlanLookupService {

    private final PlannedTaskRepository plannedTaskRepository;

    /** True if the planner already saved tasks for this job. Plans are saved all-or-nothing. */
    public boolean hasPlan(UUID jobId) {
        return !plannedTaskRepository.findByJobIdOrderBySequenceNumberAsc(jobId).isEmpty();
    }
}