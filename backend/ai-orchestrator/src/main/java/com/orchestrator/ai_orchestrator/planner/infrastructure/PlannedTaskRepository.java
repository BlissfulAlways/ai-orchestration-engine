package com.orchestrator.ai_orchestrator.planner.infrastructure;

import com.orchestrator.ai_orchestrator.planner.domain.PlannedTask;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;
import java.util.List;
import java.util.UUID;

@Repository
public interface PlannedTaskRepository extends JpaRepository<PlannedTask, UUID> {

    List<PlannedTask> findByJobIdOrderBySequenceNumberAsc(UUID jobId);

    long countByJobId(UUID jobId);
}
