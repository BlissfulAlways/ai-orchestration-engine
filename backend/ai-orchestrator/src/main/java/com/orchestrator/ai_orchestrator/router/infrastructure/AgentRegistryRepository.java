package com.orchestrator.ai_orchestrator.router.infrastructure;

import com.orchestrator.ai_orchestrator.router.domain.AgentRegistryEntry;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface AgentRegistryRepository extends JpaRepository<AgentRegistryEntry, UUID> {

    @Query(value = "SELECT * FROM agent_registry WHERE is_active = true AND :taskType = ANY(supported_task_types) LIMIT 1", nativeQuery = true)
    Optional<AgentRegistryEntry> findActiveAgentForTaskType(@Param("taskType") String taskType);
}
