package com.orchestrator.ai_orchestrator.taskqueue.domain;

import jakarta.persistence.*;
import lombok.*;
import java.time.LocalDateTime;
import java.util.UUID;

@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
@Entity
@Table(name = "task_queue")
public class QueuedTask {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(name = "id", nullable = false)
    private UUID id;

    @Column(name = "job_id", nullable = false)
    private UUID jobId;

    @Column(name = "planned_task_id", nullable = false)
    private UUID plannedTaskId;

    @Column(name = "required_agent_type", nullable = false)
    private String requiredAgentType;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false)
    private QueuedTaskStatus status;

    @Column(name = "retry_count", nullable = false)
    private Integer retryCount;

    @Column(name = "queued_at", nullable = false, updatable = false)
    private LocalDateTime queuedAt;

    @Column(name = "picked_up_at", nullable = true)
    private LocalDateTime pickedUpAt;
}
