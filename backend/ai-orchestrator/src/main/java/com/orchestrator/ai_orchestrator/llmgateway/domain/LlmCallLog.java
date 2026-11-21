package com.orchestrator.ai_orchestrator.llmgateway.domain;

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
@Table(name = "llm_call_logs")
public class LlmCallLog implements Identifiable {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(name = "id", nullable = false)
    @ToString.Include
    private UUID id;

    /** The job this call belongs to. Null only for calls made outside a job. */
    @Column(name = "job_id", nullable = true)
    @ToString.Include
    private UUID jobId;

    @Column(name = "calling_component", nullable = false)
    @ToString.Include
    private String callingComponent;

    @Column(name = "model_name", nullable = false)
    private String modelName;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "prompt", nullable = false, columnDefinition = "jsonb")
    private String prompt;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "response", nullable = true, columnDefinition = "jsonb")
    private String response;

    @Column(name = "tokens_used", nullable = true)
    private Integer tokensUsed;

    /** Total time for the logical call, including retries and waits. */
    @Column(name = "latency_ms", nullable = true)
    private Integer latencyMs;

    @Column(name = "status", nullable = false)
    @ToString.Include
    private String status;

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