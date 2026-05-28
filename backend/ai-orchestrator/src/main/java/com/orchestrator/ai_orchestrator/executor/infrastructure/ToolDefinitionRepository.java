package com.orchestrator.ai_orchestrator.executor.infrastructure;

import com.orchestrator.ai_orchestrator.executor.domain.ToolDefinition;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;
import java.util.List;
import java.util.UUID;

@Repository
public interface ToolDefinitionRepository extends JpaRepository<ToolDefinition, UUID> {

    List<ToolDefinition> findByIsActiveTrue();
}
