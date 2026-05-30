package com.orchestrator.ai_orchestrator.llmgateway.domain;

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
@Table(name = "llm_call_logs")
public class LlmCallLog {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(name = "id", nullable = false)
    private UUID id;

    @Column(name = "calling_component", nullable = false)
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

    @Column(name = "status", nullable = false)
    private String status;

    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;
}
