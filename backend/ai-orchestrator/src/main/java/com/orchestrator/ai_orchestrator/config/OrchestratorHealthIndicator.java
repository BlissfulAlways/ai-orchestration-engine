package com.orchestrator.ai_orchestrator.config;

import com.orchestrator.ai_orchestrator.llmgateway.service.LlmCircuitBreaker;
import com.orchestrator.ai_orchestrator.observer.service.ObserverService;
import com.orchestrator.ai_orchestrator.taskqueue.domain.QueuedTaskStatus;
import com.orchestrator.ai_orchestrator.taskqueue.infrastructure.TaskQueueRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.actuate.health.HealthIndicator;
import org.springframework.stereotype.Component;

/**
 * Adds an "orchestrator" section to /actuator/health with the numbers an operator checks first:
 * how much work is waiting, how much is running, whether audit events are being lost, and
 * whether the Gemini circuit breaker is refusing calls.
 *
 * <p>The status stays UP when the breaker is open: the application itself is healthy and keeps
 * accepting jobs; only model calls are paused. The state is shown as a detail instead.
 */
@Component
@RequiredArgsConstructor
public class OrchestratorHealthIndicator implements HealthIndicator {

    private final TaskQueueRepository taskQueueRepository;
    private final ObserverService observerService;
    private final LlmCircuitBreaker circuitBreaker;

    @Override
    public Health health() {
        return Health.up()
                .withDetail("pendingTasks", taskQueueRepository.countByStatus(QueuedTaskStatus.PENDING))
                .withDetail("processingTasks", taskQueueRepository.countByStatus(QueuedTaskStatus.PROCESSING))
                .withDetail("droppedEvents", observerService.droppedEventCount())
                .withDetail("llmCircuit", circuitBreaker.state().name())
                .build();
    }
}