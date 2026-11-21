package com.orchestrator.ai_orchestrator.executor.service;

import com.orchestrator.ai_orchestrator.resultstore.domain.TaskResult;
import com.orchestrator.ai_orchestrator.resultstore.domain.TaskResultStatus;
import com.orchestrator.ai_orchestrator.resultstore.infrastructure.TaskResultRepository;
import com.orchestrator.ai_orchestrator.taskqueue.domain.QueuedTask;
import com.orchestrator.ai_orchestrator.taskqueue.domain.QueuedTaskStatus;
import com.orchestrator.ai_orchestrator.taskqueue.infrastructure.TaskQueueRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;

/**
 * Records what happened to a task. Each public method is ONE database transaction:
 * the result row and the queue row change together, or neither changes.
 *
 * <p>Why a separate class: Spring applies {@code @Transactional} through a proxy, and a proxy
 * is bypassed when a class calls its own method. Keeping these methods in their own bean
 * guarantees the transaction really starts.
 *
 * <p>Why not make the whole task execution transactional: execution includes LLM calls that can
 * take minutes, and a transaction holds a database connection the whole time. The transaction
 * here covers only the two quick writes.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class TaskOutcomeService {

    private final TaskQueueRepository taskQueueRepository;
    private final TaskResultRepository taskResultRepository;

    /** Task finished successfully: store the result and mark the queue row COMPLETED. */
    @Transactional
    public void recordSuccess(QueuedTask queuedTask, String resultContent) {
        saveResultIfAbsent(queuedTask, TaskResultStatus.COMPLETED, resultContent, null);
        queuedTask.setStatus(QueuedTaskStatus.COMPLETED);
        taskQueueRepository.save(queuedTask);
    }

    /** Task failed for good: store a FAILED result and mark the queue row FAILED. */
    @Transactional
    public void recordFailure(QueuedTask queuedTask, String reason) {
        saveResultIfAbsent(queuedTask, TaskResultStatus.FAILED, null, reason);
        queuedTask.setStatus(QueuedTaskStatus.FAILED);
        taskQueueRepository.save(queuedTask);
    }

    /** Puts the task back in the queue for another attempt. */
    @Transactional
    public void scheduleRetry(QueuedTask queuedTask) {
        queuedTask.setStatus(QueuedTaskStatus.PENDING);
        queuedTask.setRetryCount(queuedTask.getRetryCount() + 1);
        queuedTask.setPickedUpAt(null);
        taskQueueRepository.save(queuedTask);
    }

    /**
     * Inserts the result unless one already exists. A task can be attempted twice (for example
     * after a timeout rescue while the first attempt was still finishing); the database allows
     * only one result per task, so the second attempt must not try to insert another.
     */
    private void saveResultIfAbsent(QueuedTask queuedTask, TaskResultStatus status,
                                    String content, String failureReason) {
        if (taskResultRepository.existsByPlannedTaskId(queuedTask.getPlannedTaskId())) {
            log.warn("Result already exists for plannedTaskId={}; keeping the first one",
                    queuedTask.getPlannedTaskId());
            return;
        }
        Instant now = Instant.now();
        taskResultRepository.save(TaskResult.builder()
                .jobId(queuedTask.getJobId())
                .plannedTaskId(queuedTask.getPlannedTaskId())
                .status(status)
                .resultContent(content)
                .failureReason(failureReason)
                .createdAt(now)
                .updatedAt(now)
                .build());
    }
}