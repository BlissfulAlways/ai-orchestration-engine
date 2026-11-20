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

    /**
     * Atomically claims the next runnable task for an agent type, in ONE SQL statement.
     *
     * <p>A task is runnable when:
     * <ol>
     *   <li>it is PENDING and belongs to the requested agent type, and</li>
     *   <li>every task with a LOWER sequence number in the same job already has a settled
     *       result (COMPLETED or FAILED). This preserves the plan's order, because each task
     *       is given the results of the earlier ones as context.</li>
     * </ol>
     *
     * <p>{@code FOR UPDATE OF q SKIP LOCKED} locks only the queue row and makes concurrent
     * workers skip rows another worker holds instead of waiting. "OF q" matters: without it
     * the join would lock planned_tasks rows as well.
     *
     * <p>Limitation: tasks of one job now run strictly one after another, even if they are
     * independent. Parallel execution needs explicit task dependencies (planned for Phase 6).
     */
    @Transactional
    @Query(value = """
            UPDATE task_queue
               SET status = 'PROCESSING', picked_up_at = :pickedUpAt
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
            @Param("pickedUpAt") LocalDateTime pickedUpAt
    );

    List<QueuedTask> findByJobId(UUID jobId);

    List<QueuedTask> findByStatus(QueuedTaskStatus status);

    /**
     * Tasks in the given status that were picked up before {@code cutoff}.
     * The database does the filtering; the application never loads the whole table.
     */
    List<QueuedTask> findByStatusAndPickedUpAtBefore(QueuedTaskStatus status, LocalDateTime cutoff);
}