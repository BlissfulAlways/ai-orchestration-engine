package com.orchestrator.ai_orchestrator.llmgateway.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.orchestrator.ai_orchestrator.llmgateway.domain.LlmCallLog;
import com.orchestrator.ai_orchestrator.llmgateway.infrastructure.LlmCallLogRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

@Slf4j
@Service
@RequiredArgsConstructor
public class LlmGatewayService {

    private final LlmCallLogRepository llmCallLogRepository;
    private final ObjectMapper objectMapper;

    @Value("${gemini.api.key}")
    private String apiKey;

    @Value("${gemini.api.model}")
    private String model;

    @Value("${gemini.api.endpoint}")
    private String endpoint;

    public String call(String callingComponent, String systemPrompt, String userPrompt) {
        String promptJson = null;
        String responseBody = null;

        try {
            Map<String, Object> requestBody = Map.of(
                "contents", List.of(
                    Map.of(
                        "role", "user",
                        "parts", List.of(Map.of("text", systemPrompt))
                    ),
                    Map.of(
                        "role", "model",
                        "parts", List.of(Map.of("text", "Understood. I will follow these instructions."))
                    ),
                    Map.of(
                        "role", "user",
                        "parts", List.of(Map.of("text", userPrompt))
                    )
                )
            );

            promptJson = objectMapper.writeValueAsString(requestBody);

            HttpClient httpClient = HttpClient.newHttpClient();

            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(endpoint + "/" + model + ":generateContent"))
                    .header("Content-Type", "application/json")
                    .header("x-goog-api-key", apiKey)
                    .POST(HttpRequest.BodyPublishers.ofString(promptJson))
                    .build();

            HttpResponse<String> httpResponse = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            responseBody = httpResponse.body();

            JsonNode jsonNode = objectMapper.readTree(responseBody);
            String responseText = jsonNode
                    .get("candidates")
                    .get(0)
                    .get("content")
                    .get("parts")
                    .get(0)
                    .get("text")
                    .asText();

            int tokensUsed = jsonNode
                    .get("usageMetadata")
                    .get("totalTokenCount")
                    .asInt();

            LlmCallLog log = LlmCallLog.builder()
                    .callingComponent(callingComponent)
                    .modelName(model)
                    .prompt(promptJson)
                    .response(responseBody)
                    .tokensUsed(tokensUsed)
                    .status("SUCCESS")
                    .createdAt(LocalDateTime.now())
                    .build();

            llmCallLogRepository.save(log);

            return responseText;

        } catch (Exception e) {
            log.error("LlmGateway call failed: component={} error={}", callingComponent, e.getMessage());

            LlmCallLog failedLog = LlmCallLog.builder()
                    .callingComponent(callingComponent)
                    .modelName(model)
                    .prompt(promptJson != null ? promptJson : "{}")
                    .response(responseBody)
                    .tokensUsed(null)
                    .status("FAILED")
                    .createdAt(LocalDateTime.now())
                    .build();

            llmCallLogRepository.save(failedLog);

            throw new RuntimeException("LLM call failed for component: " + callingComponent, e);
        }
    }
}
