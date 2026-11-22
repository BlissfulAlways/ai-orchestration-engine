package com.orchestrator.ai_orchestrator.taskqueue.infrastructure;

import com.orchestrator.ai_orchestrator.taskqueue.domain.QueuedTask;
import com.orchestrator.ai_orchestrator.taskqueue.domain.QueuedTaskStatus;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface TaskQueueRepository extends JpaRepository<QueuedTask, UUID> {

    /**
     * Atomically claims the next runnable task for an agent type, in ONE SQL statement.
     *
     * <p>A task is runnable when:
     * <ol>
     *   <li>it is PENDING and belongs to the requested agent type, and</li>
     *   <li>every task with a LOWER sequence number in the same job already has a settled
     *       result (COMPLETED or FAILED), which preserves the plan's order.</li>
     * </ol>
     *
     * <p>{@code FOR UPDATE OF q SKIP LOCKED} locks only the queue row and makes concurrent
     * workers skip rows another worker holds instead of waiting.
     *
     * <p>The claim also sets heartbeat_at, so a freshly claimed task is never mistaken for a
     * stuck one.
     */
    @Transactional
    @Query(value = """
            UPDATE task_queue
               SET status = 'PROCESSING', picked_up_at = :pickedUpAt, heartbeat_at = :pickedUpAt
             WHERE id = (
                 SELECT q.id
                   FROM task_queue q
                   JOIN planned_tasks p ON p.id = q.planned_task_id
                  WHERE q.status = 'PENDING'
                    AND q.required_agent_type = :agentType
                    AND NOT EXISTS (
                        SELECT 1
                          FROM planned_tasks earlier
                         WHERE earlier.job_id = p.job_id
                           AND earlier.sequence_number < p.sequence_number
                           AND NOT EXISTS (
                               SELECT 1
                                 FROM task_results r
                                WHERE r.planned_task_id = earlier.id
                                  AND r.status IN ('COMPLETED', 'FAILED')
                           )
                    )
                  ORDER BY q.queued_at ASC, p.sequence_number ASC
                  LIMIT 1
                    FOR UPDATE OF q SKIP LOCKED
             )
            RETURNING *
            """, nativeQuery = true)
    Optional<QueuedTask> atomicPickup(
            @Param("agentType") String agentType,
            @Param("pickedUpAt") Instant pickedUpAt
    );

    List<QueuedTask> findByJobId(UUID jobId);

    List<QueuedTask> findByStatus(QueuedTaskStatus status);

    /** Counts without loading rows. Used by the health check. */
    long countByStatus(QueuedTaskStatus status);

    /**
     * Tasks marked PROCESSING whose last sign of life (heartbeat, or pickup time if there
     * never was a heartbeat) is older than {@code cutoff}. Their worker is presumed dead.
     */
    @Query("""
            SELECT t FROM QueuedTask t
             WHERE t.status = com.orchestrator.ai_orchestrator.taskqueue.domain.QueuedTaskStatus.PROCESSING
               AND COALESCE(t.heartbeatAt, t.pickedUpAt) < :cutoff
            """)
    List<QueuedTask> findStaleProcessingTasks(@Param("cutoff") Instant cutoff);

    /** Records "still alive" for every listed task that is still PROCESSING; returns rows changed. */
    @Modifying
    @Transactional
    @Query("""
            UPDATE QueuedTask t
               SET t.heartbeatAt = :now
             WHERE t.id IN :ids
               AND t.status = com.orchestrator.ai_orchestrator.taskqueue.domain.QueuedTaskStatus.PROCESSING
            """)
    int touchHeartbeat(@Param("ids") Collection<UUID> ids, @Param("now") Instant now);
}