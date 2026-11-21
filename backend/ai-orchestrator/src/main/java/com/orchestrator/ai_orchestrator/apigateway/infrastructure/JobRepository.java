package com.orchestrator.ai_orchestrator.apigateway.infrastructure;

import com.orchestrator.ai_orchestrator.apigateway.domain.Job;
import com.orchestrator.ai_orchestrator.apigateway.domain.JobStatus;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.UUID;

/**
 * Job persistence.
 *
 * <p>The update methods below are COMPARE-AND-SET operations: each one changes the job
 * only if it is currently in the state the caller expects, and returns the number of rows
 * changed (1 = success, 0 = someone else got there first or the state was unexpected).
 * Doing the check inside the UPDATE statement itself means two threads can never both win.
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
                         @Param("now") Instant now);

    /**
     * Finishes a job: links its final answer and sets the final status (COMPLETED, or
     * COMPLETED_WITH_ERRORS with a {@code note} saying what failed). Only works if the job
     * is still in {@code expectedStatus}.
     */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Transactional
    @Query("""
            UPDATE Job j
               SET j.status = :newStatus,
                   j.finalAnswerId = :finalAnswerId,
                   j.failureReason = :note,
                   j.updatedAt = :now
             WHERE j.id = :jobId
               AND j.status = :expectedStatus
            """)
    int completeJob(@Param("jobId") UUID jobId,
                    @Param("finalAnswerId") UUID finalAnswerId,
                    @Param("expectedStatus") JobStatus expectedStatus,
                    @Param("newStatus") JobStatus newStatus,
                    @Param("note") String note,
                    @Param("now") Instant now);

    /**
     * Marks a job FAILED with a reason, unless it already reached a final state.
     * A finished job must never be turned into FAILED by a late error.
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
                   com.orchestrator.ai_orchestrator.apigateway.domain.JobStatus.COMPLETED_WITH_ERRORS,
                   com.orchestrator.ai_orchestrator.apigateway.domain.JobStatus.FAILED)
            """)
    int markFailed(@Param("jobId") UUID jobId,
                   @Param("reason") String reason,
                   @Param("now") Instant now);

    /** Jobs in any of the given statuses that have made no progress since {@code cutoff}. */
    List<Job> findByStatusInAndUpdatedAtBefore(Collection<JobStatus> statuses, Instant cutoff);

    /**
     * Claims a stalled job for recovery by pushing its updated_at forward. Only succeeds if
     * the job is unchanged since it was read, so when several recoverers see the same job,
     * exactly one gets 1 back and the others skip it.
     */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Transactional
    @Query("""
            UPDATE Job j
               SET j.updatedAt = :now
             WHERE j.id = :jobId
               AND j.status = :status
               AND j.updatedAt = :previousUpdatedAt
            """)
    int claimForRecovery(@Param("jobId") UUID jobId,
                         @Param("status") JobStatus status,
                         @Param("previousUpdatedAt") Instant previousUpdatedAt,
                         @Param("now") Instant now);
}