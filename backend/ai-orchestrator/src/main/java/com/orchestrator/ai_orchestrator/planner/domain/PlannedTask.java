package com.orchestrator.ai_orchestrator.planner.domain;

import com.orchestrator.ai_orchestrator.common.EntityIdentity;
import com.orchestrator.ai_orchestrator.common.Identifiable;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;
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

    /**
     * Sequence numbers of the tasks whose results this task needs. It may start only when all
     * of them are settled. Empty means it needs nothing and can start at once.
    */
    @Builder.Default
    @JdbcTypeCode(SqlTypes.ARRAY)
    @Column(name = "depends_on", nullable = false, columnDefinition = "integer[]")
    private Integer[] dependsOn = new Integer[0];

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