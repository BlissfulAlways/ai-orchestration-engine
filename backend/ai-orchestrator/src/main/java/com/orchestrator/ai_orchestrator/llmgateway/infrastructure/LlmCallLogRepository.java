package com.orchestrator.ai_orchestrator.llmgateway.infrastructure;

import com.orchestrator.ai_orchestrator.llmgateway.domain.LlmCallLog;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;
import java.util.UUID;

@Repository
public interface LlmCallLogRepository extends JpaRepository<LlmCallLog, UUID> {
}
