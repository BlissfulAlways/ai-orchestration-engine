package com.orchestrator.ai_orchestrator.planner.domain;

import jakarta.persistence.*;
import lombok.*;
import java.time.LocalDateTime;
import java.util.UUID;

@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
@Entity
@Table(name = "planned_tasks")
public class PlannedTask {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(name = "id", nullable = false)
    private UUID id;

    @Column(name = "job_id", nullable = false)
    private UUID jobId;

    @Column(name = "sequence_number", nullable = false)
    private Integer sequenceNumber;

    @Column(name = "task_description", nullable = false, columnDefinition = "TEXT")
    private String taskDescription;

    @Column(name = "required_agent_type", nullable = false)
    private String requiredAgentType;

    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;
}
