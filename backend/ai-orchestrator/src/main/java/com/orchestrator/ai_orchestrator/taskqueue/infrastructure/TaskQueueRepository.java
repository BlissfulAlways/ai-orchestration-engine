package com.orchestrator.ai_orchestrator.taskqueue.infrastructure;

import com.orchestrator.ai_orchestrator.taskqueue.domain.QueuedTask;
import com.orchestrator.ai_orchestrator.taskqueue.domain.QueuedTaskStatus;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface TaskQueueRepository extends JpaRepository<QueuedTask, UUID> {

    Optional<QueuedTask> findFirstByStatusAndRequiredAgentTypeOrderByQueuedAtAsc(
        QueuedTaskStatus status,
        String requiredAgentType
    );

    List<QueuedTask> findByJobId(UUID jobId);
}
