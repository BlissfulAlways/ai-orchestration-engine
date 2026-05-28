package com.orchestrator.ai_orchestrator.resultstore.domain;

import jakarta.persistence.*;
import lombok.*;
import java.time.LocalDateTime;
import java.util.UUID;

@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
@Entity
@Table(name = "task_results")
public class TaskResult {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(name = "id", nullable = false)
    private UUID id;

    @Column(name = "job_id", nullable = false)
    private UUID jobId;

    @Column(name = "planned_task_id", nullable = false)
    private UUID plannedTaskId;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false)
    private TaskResultStatus status;

    @Column(name = "result_content", nullable = true, columnDefinition = "TEXT")
    private String resultContent;

    @Column(name = "failure_reason", nullable = true, columnDefinition = "TEXT")
    private String failureReason;

    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;
}
