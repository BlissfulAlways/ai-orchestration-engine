package com.orchestrator.ai_orchestrator.planner.domain;

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
@Table(name = "planned_tasks")
public class PlannedTask implements Identifiable {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(name = "id", nullable = false)
    @ToString.Include
    private UUID id;

    @Column(name = "job_id", nullable = false)
    @ToString.Include
    private UUID jobId;

    @Column(name = "sequence_number", nullable = false)
    @ToString.Include
    private Integer sequenceNumber;

    @Column(name = "task_description", nullable = false, columnDefinition = "TEXT")
    private String taskDescription;

    @Column(name = "required_agent_type", nullable = false)
    private String requiredAgentType;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Override
    public boolean equals(Object other) {
        return EntityIdentity.sameEntity(this, other);
    }

    @Override
    public int hashCode() {
        return EntityIdentity.hashOf(this);
    }
}