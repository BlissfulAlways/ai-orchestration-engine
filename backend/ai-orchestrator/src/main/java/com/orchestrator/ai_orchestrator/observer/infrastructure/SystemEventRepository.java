package com.orchestrator.ai_orchestrator.observer.infrastructure;

import com.orchestrator.ai_orchestrator.observer.domain.SystemEvent;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;
import java.util.UUID;

@Repository
public interface SystemEventRepository extends JpaRepository<SystemEvent, UUID> {
}
