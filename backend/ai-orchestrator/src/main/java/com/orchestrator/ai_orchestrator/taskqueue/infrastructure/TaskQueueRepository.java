package com.orchestrator.ai_orchestrator.taskqueue.infrastructure;

import com.orchestrator.ai_orchestrator.taskqueue.domain.QueuedTask;
import com.orchestrator.ai_orchestrator.taskqueue.domain.QueuedTaskStatus;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface TaskQueueRepository extends JpaRepository<QueuedTask, UUID> {

    @Transactional
    @Query(value = """
            UPDATE task_queue
            SET status = 'PROCESSING', picked_up_at = :pickedUpAt
            WHERE id = (
                SELECT id FROM task_queue
                WHERE status = 'PENDING'
                AND required_agent_type = :agentType
                ORDER BY queued_at ASC
                LIMIT 1
                FOR UPDATE SKIP LOCKED
            )
            RETURNING *
            """, nativeQuery = true)
    Optional<QueuedTask> atomicPickup(
            @Param("agentType") String agentType,
            @Param("pickedUpAt") LocalDateTime pickedUpAt
    );

    List<QueuedTask> findByJobId(UUID jobId);

    List<QueuedTask> findByStatus(QueuedTaskStatus status);
}
