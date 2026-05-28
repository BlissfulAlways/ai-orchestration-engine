package com.orchestrator.ai_orchestrator.resultstore.infrastructure;

import com.orchestrator.ai_orchestrator.resultstore.domain.TaskResult;
import com.orchestrator.ai_orchestrator.resultstore.domain.TaskResultStatus;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;
import java.util.List;
import java.util.UUID;

@Repository
public interface TaskResultRepository extends JpaRepository<TaskResult, UUID> {

    List<TaskResult> findByJobId(UUID jobId);

    long countByJobIdAndStatusIn(UUID jobId, List<TaskResultStatus> statuses);
}
