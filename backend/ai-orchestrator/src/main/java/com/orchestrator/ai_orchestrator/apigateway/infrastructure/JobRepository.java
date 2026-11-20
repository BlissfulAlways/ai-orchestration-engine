package com.orchestrator.ai_orchestrator.apigateway.infrastructure;

import com.orchestrator.ai_orchestrator.apigateway.domain.Job;
import com.orchestrator.ai_orchestrator.apigateway.domain.JobStatus;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.UUID;

/**
 * Job persistence.
 *
 * <p>The three update methods below are COMPARE-AND-SET operations: each one changes the job
 * only if it is currently in the status the caller expects, and returns the number of rows
 * changed (1 = success, 0 = someone else got there first or the state was unexpected).
 * Doing the check inside the UPDATE statement itself means two threads can never both win.
 * A read-then-write in Java cannot give that guarantee.
 */
@Repository
public interface JobRepository extends JpaRepository<Job, UUID> {

    /** Moves a job from {@code expectedStatus} to {@code newStatus}; returns rows changed. */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Transactional
    @Query("""
            UPDATE Job j
               SET j.status = :newStatus, j.updatedAt = :now
             WHERE j.id = :jobId
               AND j.status = :expectedStatus
            """)
    int transitionStatus(@Param("jobId") UUID jobId,
                         @Param("expectedStatus") JobStatus expectedStatus,
                         @Param("newStatus") JobStatus newStatus,
                         @Param("now") LocalDateTime now);

    /** Marks a job COMPLETED and links its final answer, only if it is still in {@code expectedStatus}. */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Transactional
    @Query("""
            UPDATE Job j
               SET j.status = :newStatus,
                   j.finalAnswerId = :finalAnswerId,
                   j.updatedAt = :now
             WHERE j.id = :jobId
               AND j.status = :expectedStatus
            """)
    int completeJob(@Param("jobId") UUID jobId,
                    @Param("finalAnswerId") UUID finalAnswerId,
                    @Param("expectedStatus") JobStatus expectedStatus,
                    @Param("newStatus") JobStatus newStatus,
                    @Param("now") LocalDateTime now);

    /**
     * Marks a job FAILED with a reason, unless it already reached a terminal state.
     * A job that is COMPLETED must never be turned into FAILED by a late error.
     */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Transactional
    @Query("""
            UPDATE Job j
               SET j.status = com.orchestrator.ai_orchestrator.apigateway.domain.JobStatus.FAILED,
                   j.failureReason = :reason,
                   j.updatedAt = :now
             WHERE j.id = :jobId
               AND j.status NOT IN (
                   com.orchestrator.ai_orchestrator.apigateway.domain.JobStatus.COMPLETED,
                   com.orchestrator.ai_orchestrator.apigateway.domain.JobStatus.FAILED)
            """)
    int markFailed(@Param("jobId") UUID jobId,
                   @Param("reason") String reason,
                   @Param("now") LocalDateTime now);
}