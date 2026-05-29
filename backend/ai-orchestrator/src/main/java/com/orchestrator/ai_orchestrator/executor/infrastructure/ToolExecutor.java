package com.orchestrator.ai_orchestrator.executor.infrastructure;

import com.orchestrator.ai_orchestrator.executor.domain.ToolDefinition;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.List;

@Slf4j
@Component
public class ToolExecutor {

    public String execute(String toolIdentifier, String input, List<ToolDefinition> availableTools) {
        ToolDefinition tool = availableTools.stream()
                .filter(t -> t.getToolIdentifier().equals(toolIdentifier))
                .findFirst()
                .orElseThrow(() -> new RuntimeException("Tool not found: " + toolIdentifier));

        log.info("Executing tool={} input={}", toolIdentifier, input);

        return switch (toolIdentifier) {
            case "web_search" -> simulateWebSearch(input);
            case "text_summarizer" -> simulateTextSummarizer(input);
            case "text_writer" -> simulateTextWriter(input);
            default -> throw new RuntimeException("No implementation for tool: " + toolIdentifier);
        };
    }

    private String simulateWebSearch(String query) {
        return "Search results for '" + query + "': [Simulated result 1] [Simulated result 2] [Simulated result 3]";
    }

    private String simulateTextSummarizer(String text) {
        return "Summary of provided text: " + text.substring(0, Math.min(text.length(), 100)) + "...";
    }

    private String simulateTextWriter(String instructions) {
        return "Written content based on instructions: " + instructions;
    }
}
