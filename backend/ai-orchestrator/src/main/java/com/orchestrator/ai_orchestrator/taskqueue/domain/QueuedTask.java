package com.orchestrator.ai_orchestrator.taskqueue.domain;

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
@Table(name = "task_queue")
public class QueuedTask implements Identifiable {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(name = "id", nullable = false)
    @ToString.Include
    private UUID id;

    @Column(name = "job_id", nullable = false)
    @ToString.Include
    private UUID jobId;

    @Column(name = "planned_task_id", nullable = false)
    private UUID plannedTaskId;

    @Column(name = "required_agent_type", nullable = false)
    private String requiredAgentType;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false)
    @ToString.Include
    private QueuedTaskStatus status;

    @Column(name = "retry_count", nullable = false)
    private Integer retryCount;

    @Column(name = "queued_at", nullable = false, updatable = false)
    private Instant queuedAt;

    @Column(name = "picked_up_at", nullable = true)
    private Instant pickedUpAt;

    /** Updated every few seconds while a worker runs the task. Silence means the worker died. */
    @Column(name = "heartbeat_at", nullable = true)
    private Instant heartbeatAt;

    @Override
    public boolean equals(Object other) {
        return EntityIdentity.sameEntity(this, other);
    }

    @Override
    public int hashCode() {
        return EntityIdentity.hashOf(this);
    }
}