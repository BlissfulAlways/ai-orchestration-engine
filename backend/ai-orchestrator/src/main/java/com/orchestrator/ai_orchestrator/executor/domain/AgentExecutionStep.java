package com.orchestrator.ai_orchestrator.executor.domain;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;
import java.time.LocalDateTime;
import java.util.UUID;

@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
@Entity
@Table(name = "agent_execution_steps")
public class AgentExecutionStep {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(name = "id", nullable = false)
    private UUID id;

    @Column(name = "job_id", nullable = false)
    private UUID jobId;

    @Column(name = "task_queue_id", nullable = false)
    private UUID taskQueueId;

    @Column(name = "step_number", nullable = false)
    private Integer stepNumber;

    @Column(name = "agent_thought", nullable = false, columnDefinition = "TEXT")
    private String agentThought;

    @Column(name = "tool_called", nullable = true)
    private String toolCalled;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "tool_result", nullable = true, columnDefinition = "jsonb")
    private String toolResult;

    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;
}
