package com.orchestrator.ai_orchestrator.aggregator.infrastructure;

import com.orchestrator.ai_orchestrator.aggregator.domain.FinalAnswer;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface FinalAnswerRepository extends JpaRepository<FinalAnswer, UUID> {

    Optional<FinalAnswer> findByJobId(UUID jobId);
}
