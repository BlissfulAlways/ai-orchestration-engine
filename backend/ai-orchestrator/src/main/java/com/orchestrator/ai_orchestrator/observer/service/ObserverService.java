package com.orchestrator.ai_orchestrator.observer.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.orchestrator.ai_orchestrator.observer.domain.SystemEvent;
import com.orchestrator.ai_orchestrator.observer.infrastructure.SystemEventRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.Map;
import java.util.UUID;

@Slf4j
@Service
@RequiredArgsConstructor
public class ObserverService {

    private final SystemEventRepository systemEventRepository;
    private final ObjectMapper objectMapper;

    public void emit(String sourceComponent, String eventType, UUID jobId, Map<String, Object> payload) {
        try {
            String payloadJson = objectMapper.writeValueAsString(payload);

            SystemEvent event = SystemEvent.builder()
                    .sourceComponent(sourceComponent)
                    .eventType(eventType)
                    .jobId(jobId)
                    .payload(payloadJson)
                    .createdAt(LocalDateTime.now())
                    .build();

            systemEventRepository.save(event);

        } catch (Exception e) {
            log.error("Observer failed to emit event: source={} type={} jobId={} error={}",
                    sourceComponent, eventType, jobId, e.getMessage());
        }
    }
}
