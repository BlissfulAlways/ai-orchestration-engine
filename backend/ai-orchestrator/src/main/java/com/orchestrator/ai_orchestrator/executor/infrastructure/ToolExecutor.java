package com.orchestrator.ai_orchestrator.executor.infrastructure;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.orchestrator.ai_orchestrator.common.ErrorMessages;
import com.orchestrator.ai_orchestrator.common.TextUtils;
import com.orchestrator.ai_orchestrator.executor.domain.ToolDefinition;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;

/**
 * Runs the tools an agent may call. Tools receive their arguments as JSON, exactly as the
 * model produced them, and return plain text for the model to read.
 *
 * <p>Security note: fetch_page only accepts http/https URLs and reads a bounded amount of
 * data. Blocking private and internal addresses is planned for Phase 5.
 */
@Slf4j
@Component
public class ToolExecutor {

    private static final String TAVILY_SEARCH_URL = "https://api.tavily.com/search";
    private static final Duration TOOL_REQUEST_TIMEOUT = Duration.ofSeconds(30);
    /** Stop reading a page after this many bytes, so one huge page cannot exhaust memory. */
    private static final int MAX_FETCH_BYTES = 500_000;
    private static final int MAX_PAGE_TEXT_CHARS = 3_000;

    private final ObjectMapper objectMapper;
    private final HttpClient httpClient;
    private final String tavilyApiKey;

    public ToolExecutor(ObjectMapper objectMapper,
                        HttpClient httpClient,
                        @Value("${tavily.api.key}") String tavilyApiKey) {
        this.objectMapper = objectMapper;
        this.httpClient = httpClient;
        this.tavilyApiKey = tavilyApiKey;
    }

    public String execute(String toolIdentifier, JsonNode arguments, List<ToolDefinition> availableTools) {
        boolean registered = availableTools.stream()
                .anyMatch(tool -> tool.getToolIdentifier().equals(toolIdentifier));
        if (!registered) {
            throw new IllegalArgumentException("Tool not found: " + toolIdentifier);
        }

        log.info("Executing tool={} args={}", toolIdentifier, TextUtils.truncate(String.valueOf(arguments), 200));

        return switch (toolIdentifier) {
            case "web_search" -> webSearch(requireText(arguments, toolIdentifier, "query"));
            case "fetch_page" -> fetchPage(requireText(arguments, toolIdentifier, "url"));
            default -> throw new IllegalArgumentException("No implementation for tool: " + toolIdentifier);
        };
    }

    // ---------------------------------------------------------------- web_search

    private String webSearch(String query) {
        ObjectNode body = objectMapper.createObjectNode();
        body.put("api_key", tavilyApiKey);
        body.put("query", query);
        body.put("search_depth", "basic");
        body.put("max_results", 5);
        body.put("include_answer", true);

        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(TAVILY_SEARCH_URL))
                .timeout(TOOL_REQUEST_TIMEOUT)
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body.toString()))
                .build();

        HttpResponse<String> response = send(request, HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() / 100 != 2) {
            throw new IllegalStateException("Web search returned HTTP " + response.statusCode());
        }

        JsonNode json = readTree(response.body());
        StringBuilder result = new StringBuilder();

        JsonNode answer = json.path("answer");
        if (!answer.isMissingNode() && !answer.isNull()) {
            result.append("Quick Answer: ").append(answer.asText()).append("\n\n");
        }

        JsonNode results = json.path("results");
        if (results.isArray()) {
            result.append("Search Results:\n");
            for (JsonNode item : results) {
                result.append("Title: ").append(item.path("title").asText("")).append("\n");
                result.append("URL: ").append(item.path("url").asText("")).append("\n");
                result.append("Content: ").append(item.path("content").asText("")).append("\n\n");
            }
        }
        return result.toString();
    }

    // ---------------------------------------------------------------- fetch_page

    private String fetchPage(String rawUrl) {
        URI uri = parseHttpUri(rawUrl);

        HttpRequest request = HttpRequest.newBuilder()
                .uri(uri)
                .timeout(TOOL_REQUEST_TIMEOUT)
                .header("User-Agent", "Mozilla/5.0")
                .GET()
                .build();

        HttpResponse<InputStream> response = send(request, HttpResponse.BodyHandlers.ofInputStream());
        try (InputStream in = response.body()) {
            if (response.statusCode() / 100 != 2) {
                throw new IllegalStateException("Page fetch returned HTTP " + response.statusCode());
            }
            String html = new String(in.readNBytes(MAX_FETCH_BYTES), StandardCharsets.UTF_8);
            return TextUtils.truncate(htmlToText(html), MAX_PAGE_TEXT_CHARS);
        } catch (IOException e) {
            throw new IllegalStateException("Page read failed: " + ErrorMessages.describe(e), e);
        }
    }

    /** Crude HTML-to-text. Good enough to give the model readable content. */
    private String htmlToText(String html) {
        return html
                .replaceAll("(?is)<script[^>]*>.*?</script>", "")
                .replaceAll("(?is)<style[^>]*>.*?</style>", "")
                .replaceAll("<[^>]+>", " ")
                .replace("&nbsp;", " ")
                .replace("&amp;", "&")
                .replace("&lt;", "<")
                .replace("&gt;", ">")
                .replaceAll("\\s+", " ")
                .trim();
    }

    private URI parseHttpUri(String rawUrl) {
        URI uri;
        try {
            uri = URI.create(rawUrl.trim());
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("Invalid URL: " + TextUtils.truncate(rawUrl, 200));
        }
        String scheme = uri.getScheme();
        boolean httpOrHttps = "http".equalsIgnoreCase(scheme) || "https".equalsIgnoreCase(scheme);
        if (!httpOrHttps || uri.getHost() == null) {
            throw new IllegalArgumentException("Only http and https URLs are allowed");
        }
        return uri;
    }

    // ---------------------------------------------------------------- helpers

    private String requireText(JsonNode arguments, String toolIdentifier, String field) {
        String value = arguments == null ? "" : arguments.path(field).asText("").trim();
        if (value.isEmpty()) {
            throw new IllegalArgumentException(
                    "Tool '" + toolIdentifier + "' requires a non-empty argument '" + field + "'");
        }
        return value;
    }

    private JsonNode readTree(String body) {
        try {
            return objectMapper.readTree(body);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Tool response was not valid JSON", e);
        }
    }

    /** Sends a request, turning checked exceptions into one unchecked failure the loop can report. */
    private <T> HttpResponse<T> send(HttpRequest request, HttpResponse.BodyHandler<T> handler) {
        try {
            return httpClient.send(request, handler);
        } catch (IOException e) {
            throw new IllegalStateException("Request failed: " + ErrorMessages.describe(e), e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted during tool request", e);
        }
    }
}