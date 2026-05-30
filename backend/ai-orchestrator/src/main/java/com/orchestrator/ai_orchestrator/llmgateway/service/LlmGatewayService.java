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
import java.util.concurrent.Semaphore;

@Slf4j
@Service
@RequiredArgsConstructor
public class LlmGatewayService {

    private final LlmCallLogRepository llmCallLogRepository;
    private final ObjectMapper objectMapper;

    private final Semaphore rateLimiter = new Semaphore(1);

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
            rateLimiter.acquire();

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
                responseBody = sendRequest(promptJson);
                JsonNode jsonNode = objectMapper.readTree(responseBody);

                int attempts = 0;
                while (jsonNode.has("error") && attempts < 5) {
                    int errorCode = jsonNode.get("error").get("code").asInt();
                    if (errorCode != 429 && errorCode != 503) {
                        throw new RuntimeException("Gemini error " + errorCode + ": "
                                + jsonNode.get("error").get("message").asText());
                    }
                    attempts++;
                    int waitSecs = extractRetryDelay(jsonNode);
                    log.warn("Gemini unavailable. Attempt {} of 5. Waiting {}s. component={}",
                            attempts, waitSecs, callingComponent);
                    Thread.sleep(waitSecs * 1000L);
                    responseBody = sendRequest(promptJson);
                    jsonNode = objectMapper.readTree(responseBody);
                }

                if (jsonNode.has("error")) {
                    throw new RuntimeException("Gemini still failing after 5 retries: "
                            + jsonNode.get("error").get("message").asText());
                }

                log.info("Gemini call succeeded component={}", callingComponent);

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

                LlmCallLog callLog = LlmCallLog.builder()
                        .callingComponent(callingComponent)
                        .modelName(model)
                        .prompt(promptJson)
                        .response(responseBody)
                        .tokensUsed(tokensUsed)
                        .status("SUCCESS")
                        .createdAt(LocalDateTime.now())
                        .build();

                llmCallLogRepository.save(callLog);

                return responseText;

            } finally {
                rateLimiter.release();
            }

        } catch (Exception e) {
            log.error("LlmGateway call failed: component={} error={}", callingComponent, e.getMessage(), e);

            try {
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
            } catch (Exception logEx) {
                log.error("Failed to save LLM call log: {}", logEx.getMessage());
            }

            throw new RuntimeException("LLM call failed for component: " + callingComponent, e);
        }
    }

    private String sendRequest(String promptJson) throws Exception {
        HttpClient httpClient = HttpClient.newHttpClient();
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(endpoint + "/" + model + ":generateContent"))
                .header("Content-Type", "application/json")
                .header("x-goog-api-key", apiKey)
                .POST(HttpRequest.BodyPublishers.ofString(promptJson))
                .build();
        HttpResponse<String> httpResponse = httpClient.send(request,
                HttpResponse.BodyHandlers.ofString());
        return httpResponse.body();
    }

    private int extractRetryDelay(JsonNode jsonNode) {
        try {
            String message = jsonNode.get("error").get("message").asText();
            if (message.contains("Please retry in")) {
                String[] parts = message.split("Please retry in ");
                double seconds = Double.parseDouble(parts[1].split("s")[0].trim());
                return (int) Math.ceil(seconds);
            }
            JsonNode details = jsonNode.get("error").get("details");
            if (details != null && details.isArray()) {
                for (JsonNode detail : details) {
                    if (detail.has("@type") && "type.googleapis.com/google.rpc.RetryInfo"
                            .equals(detail.get("@type").asText())) {
                        String retryDelay = detail.get("retryDelay").asText();
                        return Integer.parseInt(retryDelay.replace("s", "").trim());
                    }
                }
            }
        } catch (Exception e) {
            log.warn("Could not extract retryDelay, defaulting to 60s");
        }
        return 60;
    }
}
