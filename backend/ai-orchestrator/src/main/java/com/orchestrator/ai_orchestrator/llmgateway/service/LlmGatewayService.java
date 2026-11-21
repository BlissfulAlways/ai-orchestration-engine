package com.orchestrator.ai_orchestrator.llmgateway.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.orchestrator.ai_orchestrator.common.TextUtils;
import com.orchestrator.ai_orchestrator.llmgateway.domain.LlmCallLog;
import com.orchestrator.ai_orchestrator.llmgateway.infrastructure.LlmCallLogRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Semaphore;
import java.util.concurrent.ThreadLocalRandom;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The only class that talks to Gemini.
 *
 * <p>Guarantees:
 * <ul>
 *   <li>Retries rate-limit and server errors with exponential backoff and jitter.</li>
 *   <li>The concurrency permit is held ONLY while a request is in flight. Waiting between
 *       retries holds nothing, so one rate-limited call can no longer freeze every other call.</li>
 *   <li>Every response is checked field by field; a malformed or blocked response becomes an
 *       {@link LlmException} with a clear message, never a NullPointerException.</li>
 *   <li>One row per logical call is written to llm_call_logs (success or failure).</li>
 * </ul>
 */
@Slf4j
@Service
public class LlmGatewayService {

    private static final Pattern RETRY_IN_MESSAGE =
            Pattern.compile("retry in ([0-9.]+)s", Pattern.CASE_INSENSITIVE);

    private static final long BASE_BACKOFF_MILLIS = 2_000;
    private static final long MAX_BACKOFF_MILLIS = 60_000;
    /** Longest server-suggested wait we are willing to honour. */
    private static final long MAX_SERVER_HINT_MILLIS = 120_000;
    private static final int MAX_LOGGED_RAW_CHARS = 2_000;

    private final LlmCallLogRepository llmCallLogRepository;
    private final ObjectMapper objectMapper;
    private final HttpClient httpClient;
    private final String apiKey;
    private final String model;
    private final String endpoint;
    private final int maxAttempts;
    private final Duration requestTimeout;
    private final int maxOutputTokens;
    private final Semaphore concurrencyLimiter;

    // Settings with a ":default" need no entry in application.yaml.
    public LlmGatewayService(
            LlmCallLogRepository llmCallLogRepository,
            ObjectMapper objectMapper,
            HttpClient httpClient,
            @Value("${gemini.api.key}") String apiKey,
            @Value("${gemini.api.model}") String model,
            @Value("${gemini.api.endpoint}") String endpoint,
            @Value("${gemini.api.max-attempts:5}") int maxAttempts,
            @Value("${gemini.api.request-timeout-seconds:60}") int requestTimeoutSeconds,
            @Value("${gemini.api.max-concurrent-calls:2}") int maxConcurrentCalls,
            @Value("${gemini.api.max-output-tokens:8192}") int maxOutputTokens) {
        this.llmCallLogRepository = llmCallLogRepository;
        this.objectMapper = objectMapper;
        this.httpClient = httpClient;
        this.apiKey = apiKey;
        this.model = model;
        this.endpoint = endpoint;
        this.maxAttempts = maxAttempts;
        this.requestTimeout = Duration.ofSeconds(requestTimeoutSeconds);
        this.maxOutputTokens = maxOutputTokens;
        this.concurrencyLimiter = new Semaphore(maxConcurrentCalls);
    }

    /**
     * Convenience for a one-shot text answer. Throws if the answer is empty or was cut off,
     * so callers can use the returned text directly.
     */
    public String call(UUID jobId, String callingComponent, String systemPrompt, String userPrompt) {
        LlmResponse response = generate(LlmRequest.text(jobId, callingComponent, systemPrompt, userPrompt));
        String text = response.text().trim();
        if (text.isEmpty()) {
            throw new LlmException("Gemini returned an empty answer (finishReason="
                    + response.finishReason() + ")", false);
        }
        if (!response.finishedNormally()) {
            throw new LlmException("Gemini answer is incomplete (finishReason="
                    + response.finishReason() + ")", false);
        }
        return text;
    }

    /** Sends the request, retrying temporary failures, and returns the checked response. */
    public LlmResponse generate(LlmRequest request) {
        long startNanos = System.nanoTime();
        String promptJson = null;
        String responseBody = null;
        try {
            promptJson = buildRequestBody(request);

            for (int attempt = 1; ; attempt++) {
                boolean retryable;
                String failure;
                long serverSuggestedDelayMillis = -1;

                try {
                    HttpResponse<String> http = sendOnce(promptJson);
                    responseBody = http.body();

                    if (http.statusCode() == 200) {
                        LlmResponse parsed = parseSuccessBody(responseBody);
                        recordCall(request, promptJson, responseBody,
                                parsed.totalTokens(), "SUCCESS", elapsedMillis(startNanos));
                        log.info("Gemini call succeeded component={} jobId={} attempts={} tokens={}",
                                request.callingComponent(), request.jobId(), attempt, parsed.totalTokens());
                        return parsed;
                    }

                    retryable = isRetryableStatus(http.statusCode());
                    failure = "HTTP " + http.statusCode() + ": "
                            + TextUtils.truncate(extractErrorMessage(responseBody), 300);
                    serverSuggestedDelayMillis = extractRetryDelayMillis(responseBody);
                } catch (IOException e) {
                    // Network trouble and timeouts are temporary, so they are retried.
                    retryable = true;
                    failure = e.getClass().getSimpleName() + ": " + e.getMessage();
                }

                if (!retryable || attempt >= maxAttempts) {
                    throw new LlmException("Gemini call failed after " + attempt
                            + " attempt(s): " + failure, retryable);
                }

                long delayMillis = computeBackoffMillis(attempt, serverSuggestedDelayMillis);
                log.warn("Gemini call failed (attempt {} of {}): {}. Retrying in {} ms. component={} jobId={}",
                        attempt, maxAttempts, failure, delayMillis,
                        request.callingComponent(), request.jobId());
                sleep(delayMillis); // No permit is held here.
            }
        } catch (Exception e) {
            recordCall(request, promptJson, responseBody, null, "FAILED", elapsedMillis(startNanos));
            if (e instanceof LlmException llmException) {
                throw llmException;
            }
            throw new LlmException("LLM call failed for component " + request.callingComponent(), false, e);
        }
    }

    // ---------------------------------------------------------------- sending

    /** One HTTP attempt. The concurrency permit is held for exactly this long. */
    private HttpResponse<String> sendOnce(String promptJson) throws IOException {
        boolean acquired = false;
        try {
            concurrencyLimiter.acquire();
            acquired = true;

            HttpRequest httpRequest = HttpRequest.newBuilder()
                    .uri(URI.create(endpoint + "/" + model + ":generateContent"))
                    .timeout(requestTimeout)
                    .header("Content-Type", "application/json")
                    .header("x-goog-api-key", apiKey)
                    .POST(HttpRequest.BodyPublishers.ofString(promptJson))
                    .build();
            return httpClient.send(httpRequest, HttpResponse.BodyHandlers.ofString());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt(); // Keep the "stop" signal alive for callers.
            throw new LlmException("Interrupted while calling Gemini", false, e);
        } finally {
            if (acquired) {
                concurrencyLimiter.release();
            }
        }
    }

    private String buildRequestBody(LlmRequest request) {
        ObjectNode root = objectMapper.createObjectNode();

        if (request.systemPrompt() != null && !request.systemPrompt().isBlank()) {
            root.putObject("systemInstruction")
                    .putArray("parts").addObject().put("text", request.systemPrompt());
        }

        ArrayNode contents = root.putArray("contents");
        for (JsonNode content : request.contents()) {
            contents.add(content);
        }

        JsonNode declarations = request.functionDeclarations();
        if (declarations != null && declarations.size() > 0) {
            root.putArray("tools").addObject().set("functionDeclarations", declarations);
        }

        ObjectNode generationConfig = root.putObject("generationConfig");
        generationConfig.put("temperature", request.temperature());
        generationConfig.put("maxOutputTokens", maxOutputTokens);
        if (request.responseSchema() != null) {
            generationConfig.put("responseMimeType", "application/json");
            generationConfig.set("responseSchema", request.responseSchema());
        }
        return root.toString();
    }

    // ---------------------------------------------------------------- parsing

    /** Turns a 200 response body into an {@link LlmResponse}, checking every level. */
    private LlmResponse parseSuccessBody(String body) {
        JsonNode root;
        try {
            root = objectMapper.readTree(body);
        } catch (JsonProcessingException e) {
            throw new LlmException("Gemini returned a response that is not valid JSON", false, e);
        }

        JsonNode candidates = root.path("candidates");
        if (!candidates.isArray() || candidates.size() == 0) {
            String blockReason = root.path("promptFeedback").path("blockReason").asText("");
            throw new LlmException(blockReason.isEmpty()
                    ? "Gemini returned no candidates"
                    : "Gemini blocked the prompt: " + blockReason, false);
        }

        JsonNode candidate = candidates.get(0);
        String finishReason = candidate.path("finishReason").asText("UNKNOWN");
        JsonNode content = candidate.path("content");

        StringBuilder text = new StringBuilder();
        List<LlmResponse.FunctionCall> functionCalls = new ArrayList<>();
        JsonNode parts = content.path("parts");
        if (parts.isArray()) {
            for (JsonNode part : parts) {
                if (part.has("functionCall")) {
                    JsonNode call = part.get("functionCall");
                    JsonNode args = call.has("args") ? call.get("args") : objectMapper.createObjectNode();
                    functionCalls.add(new LlmResponse.FunctionCall(call.path("name").asText(""), args));
                } else if (part.has("text") && !part.path("thought").asBoolean(false)) {
                    text.append(part.get("text").asText());
                }
            }
        }

        int totalTokens = root.path("usageMetadata").path("totalTokenCount").asInt(0);
        JsonNode modelContent = content.isMissingNode() ? null : content;
        return new LlmResponse(text.toString(), functionCalls, modelContent, totalTokens, finishReason);
    }

    private String extractErrorMessage(String body) {
        if (body == null || body.isBlank()) {
            return "(empty body)";
        }
        try {
            String message = objectMapper.readTree(body).path("error").path("message").asText("");
            return message.isEmpty() ? body : message;
        } catch (JsonProcessingException e) {
            return body; // For example an HTML error page from a proxy.
        }
    }

    // ---------------------------------------------------------------- retry policy

    private boolean isRetryableStatus(int status) {
        return status == 408 || status == 429 || status == 500
                || status == 502 || status == 503 || status == 504;
    }

    /**
     * Reads the wait time Gemini asks for, in milliseconds, or -1 if it gave none.
     * Handles decimals such as "34.5s" and both places the hint can appear.
     */
    private long extractRetryDelayMillis(String body) {
        if (body == null || body.isBlank()) {
            return -1;
        }
        try {
            JsonNode error = objectMapper.readTree(body).path("error");

            JsonNode details = error.path("details");
            if (details.isArray()) {
                for (JsonNode detail : details) {
                    if (detail.path("@type").asText("").endsWith("RetryInfo")) {
                        String delay = detail.path("retryDelay").asText("");
                        if (delay.endsWith("s")) {
                            return (long) (Double.parseDouble(delay.substring(0, delay.length() - 1)) * 1000);
                        }
                    }
                }
            }

            Matcher matcher = RETRY_IN_MESSAGE.matcher(error.path("message").asText(""));
            if (matcher.find()) {
                return (long) (Double.parseDouble(matcher.group(1)) * 1000);
            }
        } catch (Exception e) {
            log.debug("Could not read retry delay from Gemini error", e);
        }
        return -1;
    }

    /**
     * How long to wait before the next attempt.
     * <ul>
     *   <li>If Gemini told us how long, wait that (capped) plus up to 1 s of random jitter.</li>
     *   <li>Otherwise wait 2s, 4s, 8s... (capped at 60s) with "equal jitter": between half and
     *       all of that value. The randomness stops many callers retrying at the same instant.</li>
     * </ul>
     */
    private long computeBackoffMillis(int attempt, long serverSuggestedMillis) {
        ThreadLocalRandom random = ThreadLocalRandom.current();
        if (serverSuggestedMillis > 0) {
            return Math.min(serverSuggestedMillis, MAX_SERVER_HINT_MILLIS) + random.nextLong(1_000);
        }
        long ceiling = Math.min(MAX_BACKOFF_MILLIS, BASE_BACKOFF_MILLIS << Math.min(attempt - 1, 10));
        return ceiling / 2 + random.nextLong(ceiling / 2 + 1);
    }

    private void sleep(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new LlmException("Interrupted while waiting to retry", false, e);
        }
    }

    // ---------------------------------------------------------------- logging

    private long elapsedMillis(long startNanos) {
        return (System.nanoTime() - startNanos) / 1_000_000;
    }

    /** Writes the call log row. A logging failure must never break the real work. */
    private void recordCall(LlmRequest request, String promptJson, String responseBody,
                            Integer tokens, String status, long latencyMillis) {
        try {
            llmCallLogRepository.save(LlmCallLog.builder()
                    .jobId(request.jobId())
                    .callingComponent(request.callingComponent())
                    .modelName(model)
                    .prompt(promptJson != null ? promptJson : "{}")
                    .response(toJsonOrWrap(responseBody))
                    .tokensUsed(tokens)
                    .latencyMs((int) Math.min(latencyMillis, Integer.MAX_VALUE))
                    .status(status)
                    .createdAt(Instant.now())
                    .build());
        } catch (Exception e) {
            log.error("Failed to save LLM call log jobId={}", request.jobId(), e);
        }
    }

    /**
     * The response column is JSONB, so it only accepts valid JSON. Error pages (HTML) and
     * other non-JSON bodies are wrapped as {"raw": "..."} instead of failing the insert.
     */
    private String toJsonOrWrap(String body) {
        if (body == null || body.isBlank()) {
            return null;
        }
        try {
            objectMapper.readTree(body);
            return body;
        } catch (JsonProcessingException e) {
            ObjectNode wrapper = objectMapper.createObjectNode();
            wrapper.put("raw", TextUtils.truncate(body, MAX_LOGGED_RAW_CHARS));
            return wrapper.toString();
        }
    }
}