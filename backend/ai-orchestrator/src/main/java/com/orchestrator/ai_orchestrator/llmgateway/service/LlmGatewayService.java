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
            responseBody = sendRequest(promptJson);

            JsonNode jsonNode = objectMapper.readTree(responseBody);

            if (jsonNode.has("error")) {
                int errorCode = jsonNode.get("error").get("code").asInt();

                if (errorCode == 429) {
                    int retryDelaySecs = extractRetryDelay(jsonNode);
                    log.warn("Rate limited by Gemini. Waiting {}s before retry. component={}",
                            retryDelaySecs, callingComponent);
                    Thread.sleep((retryDelaySecs + 2) * 1000L);
                    responseBody = sendRequest(promptJson);
                    jsonNode = objectMapper.readTree(responseBody);

                    if (jsonNode.has("error")) {
                        throw new RuntimeException("Gemini still rate limited after retry: "
                                + jsonNode.get("error").get("message").asText());
                    }
                } else {
                    throw new RuntimeException("Gemini error " + errorCode + ": "
                            + jsonNode.get("error").get("message").asText());
                }
            }

            log.info("Gemini raw response: {}", responseBody);

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
            JsonNode details = jsonNode.get("error").get("details");
            if (details != null && details.isArray()) {
                for (JsonNode detail : details) {
                    if ("type.googleapis.com/google.rpc.RetryInfo"
                            .equals(detail.get("@type").asText())) {
                        String retryDelay = detail.get("retryDelay").asText();
                        return Integer.parseInt(retryDelay.replace("s", "").trim());
                    }
                }
            }
        } catch (Exception e) {
            log.warn("Could not extract retryDelay from Gemini response, defaulting to 60s");
        }
        return 60;
    }
}
