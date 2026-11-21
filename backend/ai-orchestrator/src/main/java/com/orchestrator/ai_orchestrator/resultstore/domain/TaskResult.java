package com.orchestrator.ai_orchestrator.resultstore.domain;

import com.orchestrator.ai_orchestrator.common.EntityIdentity;
import com.orchestrator.ai_orchestrator.common.Identifiable;
import jakarta.persistence.*;
import lombok.*;

import java.time.Instant;
import java.util.UUID;

@Getter
@Setter
@ToString(onlyExplicitlyIncluded = true)
@NoArgsConstructor
@AllArgsConstructor
@Builder
@Entity
@Table(name = "task_results")
public class TaskResult implements Identifiable {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(name = "id", nullable = false)
    @ToString.Include
    private UUID id;

    @Column(name = "job_id", nullable = false)
    private UUID jobId;

    @Column(name = "planned_task_id", nullable = false)
    @ToString.Include
    private UUID plannedTaskId;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false)
    @ToString.Include
    private TaskResultStatus status;

    @Column(name = "result_content", nullable = true, columnDefinition = "TEXT")
    private String resultContent;

    @Column(name = "failure_reason", nullable = true, columnDefinition = "TEXT")
    private String failureReason;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @Override
    public boolean equals(Object other) {
        return EntityIdentity.sameEntity(this, other);
    }

    @Override
    public int hashCode() {
        return EntityIdentity.hashOf(this);
    }
}