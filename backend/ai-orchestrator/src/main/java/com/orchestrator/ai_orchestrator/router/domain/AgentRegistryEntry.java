package com.orchestrator.ai_orchestrator.router.domain;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.Type;
import io.hypersistence.utils.hibernate.type.array.ListArrayType;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
@Entity
@Table(name = "agent_registry")
public class AgentRegistryEntry {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(name = "id", nullable = false)
    private UUID id;

    @Column(name = "agent_type", nullable = false, unique = true)
    private String agentType;

    @Type(ListArrayType.class)
    @Column(name = "supported_task_types", nullable = false, columnDefinition = "TEXT[]")
    private List<String> supportedTaskTypes;

    @Column(name = "endpoint", nullable = false)
    private String endpoint;

    @Column(name = "is_active", nullable = false)
    private Boolean isActive;

    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;
}
