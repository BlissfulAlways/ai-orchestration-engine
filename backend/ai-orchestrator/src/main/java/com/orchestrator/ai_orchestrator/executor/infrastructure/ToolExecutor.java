package com.orchestrator.ai_orchestrator.executor.infrastructure;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.orchestrator.ai_orchestrator.executor.domain.ToolDefinition;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.List;
import java.util.Map;

@Slf4j
@Component
@RequiredArgsConstructor
public class ToolExecutor {

    private final ObjectMapper objectMapper;

    @Value("${tavily.api.key}")
    private String tavilyApiKey;

    public String execute(String toolIdentifier, String input, List<ToolDefinition> availableTools) {
        availableTools.stream()
                .filter(t -> t.getToolIdentifier().equals(toolIdentifier))
                .findFirst()
                .orElseThrow(() -> new RuntimeException("Tool not found: " + toolIdentifier));

        log.info("Executing tool={} input={}", toolIdentifier, input);

        return switch (toolIdentifier) {
            case "web_search" -> webSearch(input);
            case "fetch_page" -> fetchPage(input);
            default -> throw new RuntimeException("No implementation for tool: " + toolIdentifier);
        };
    }

    private String webSearch(String query) {
        try {
            Map<String, Object> requestBody = Map.of(
                    "api_key", tavilyApiKey,
                    "query", query,
                    "search_depth", "basic",
                    "max_results", 5,
                    "include_answer", true
            );

            String requestJson = objectMapper.writeValueAsString(requestBody);

            HttpClient client = HttpClient.newHttpClient();
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create("https://api.tavily.com/search"))
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(requestJson))
                    .build();

            HttpResponse<String> response = client.send(request,
                    HttpResponse.BodyHandlers.ofString());

            JsonNode jsonNode = objectMapper.readTree(response.body());

            StringBuilder result = new StringBuilder();

            if (jsonNode.has("answer") && !jsonNode.get("answer").isNull()) {
                result.append("Quick Answer: ")
                      .append(jsonNode.get("answer").asText())
                      .append("\n\n");
            }

            if (jsonNode.has("results")) {
                result.append("Search Results:\n");
                for (JsonNode item : jsonNode.get("results")) {
                    result.append("Title: ").append(item.get("title").asText()).append("\n");
                    result.append("URL: ").append(item.get("url").asText()).append("\n");
                    result.append("Content: ").append(item.get("content").asText()).append("\n\n");
                }
            }

            return result.toString();

        } catch (Exception e) {
            log.error("web_search failed for query={} error={}", query, e.getMessage());
            throw new RuntimeException("Web search failed: " + e.getMessage(), e);
        }
    }

    private String fetchPage(String url) {
        try {
            HttpClient client = HttpClient.newHttpClient();
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(url.trim()))
                    .header("User-Agent", "Mozilla/5.0")
                    .GET()
                    .build();

            HttpResponse<String> response = client.send(request,
                    HttpResponse.BodyHandlers.ofString());

            String html = response.body();

            String text = html
                    .replaceAll("<script[^>]*>[\\s\\S]*?</script>", "")
                    .replaceAll("<style[^>]*>[\\s\\S]*?</style>", "")
                    .replaceAll("<[^>]+>", " ")
                    .replaceAll("&nbsp;", " ")
                    .replaceAll("&amp;", "&")
                    .replaceAll("&lt;", "<")
                    .replaceAll("&gt;", ">")
                    .replaceAll("\\s+", " ")
                    .trim();

            if (text.length() > 3000) {
                text = text.substring(0, 3000) + "... [truncated]";
            }

            return text;

        } catch (Exception e) {
            log.error("fetch_page failed for url={} error={}", url, e.getMessage());
            throw new RuntimeException("Page fetch failed: " + e.getMessage(), e);
        }
    }
}
