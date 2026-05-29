package com.orchestrator.ai_orchestrator.aggregator.service;

import com.orchestrator.ai_orchestrator.planner.infrastructure.PlannedTaskRepository;
import com.orchestrator.ai_orchestrator.resultstore.domain.TaskResultStatus;
import com.orchestrator.ai_orchestrator.resultstore.infrastructure.TaskResultRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.UUID;

@Slf4j
@Service
@RequiredArgsConstructor
public class AggregatorTriggerService {

    private final PlannedTaskRepository plannedTaskRepository;
    private final TaskResultRepository taskResultRepository;
    private final AggregatorService aggregatorService;

    public void checkAndTrigger(UUID jobId) {
        long totalTasks = plannedTaskRepository.countByJobId(jobId);
        long settledTasks = taskResultRepository.countByJobIdAndStatusIn(
                jobId, List.of(TaskResultStatus.COMPLETED, TaskResultStatus.FAILED));

        log.info("Aggregation check jobId={} total={} settled={}", jobId, totalTasks, settledTasks);

        if (totalTasks > 0 && settledTasks >= totalTasks) {
            log.info("All tasks settled for jobId={} triggering aggregation", jobId);
            aggregatorService.aggregate(jobId);
        }
    }
}
