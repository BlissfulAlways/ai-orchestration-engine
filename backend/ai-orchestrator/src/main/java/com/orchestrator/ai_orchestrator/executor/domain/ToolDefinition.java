package com.orchestrator.ai_orchestrator.executor.domain;

import com.orchestrator.ai_orchestrator.common.EntityIdentity;
import com.orchestrator.ai_orchestrator.common.Identifiable;
import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.Instant;
import java.util.UUID;

@Getter
@Setter
@ToString(onlyExplicitlyIncluded = true)
@NoArgsConstructor
@AllArgsConstructor
@Builder
@Entity
@Table(name = "tool_registry")
public class ToolDefinition implements Identifiable {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(name = "id", nullable = false)
    @ToString.Include
    private UUID id;

    @Column(name = "tool_name", nullable = false, unique = true)
    private String toolName;

    @Column(name = "description", nullable = false, columnDefinition = "TEXT")
    private String description;

    @Column(name = "tool_identifier", nullable = false, unique = true)
    @ToString.Include
    private String toolIdentifier;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "input_schema", nullable = false, columnDefinition = "jsonb")
    private String inputSchema;

    @Column(name = "is_active", nullable = false)
    private Boolean isActive;

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