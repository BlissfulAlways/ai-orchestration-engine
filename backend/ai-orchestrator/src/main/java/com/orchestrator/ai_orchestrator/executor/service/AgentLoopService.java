package com.orchestrator.ai_orchestrator.executor.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.orchestrator.ai_orchestrator.executor.domain.AgentExecutionStep;
import com.orchestrator.ai_orchestrator.executor.domain.ToolDefinition;
import com.orchestrator.ai_orchestrator.executor.infrastructure.AgentExecutionStepRepository;
import com.orchestrator.ai_orchestrator.executor.infrastructure.ToolExecutor;
import com.orchestrator.ai_orchestrator.llmgateway.service.LlmGatewayService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Slf4j
@Service
@RequiredArgsConstructor
public class AgentLoopService {

    private static final int MAX_STEPS = 10;
    private static final String TOOL_CALL_MARKER = "TOOL_CALL:";
    private static final String TOOL_NAME_MARKER = "TOOL_NAME:";
    private static final String TOOL_INPUT_MARKER = "TOOL_INPUT:";

    private final LlmGatewayService llmGatewayService;
    private final AgentExecutionStepRepository agentExecutionStepRepository;
    private final ToolExecutor toolExecutor;
    private final ObjectMapper objectMapper;

    public String runLoop(
            UUID jobId,
            UUID taskQueueId,
            String taskDescription,
            List<ToolDefinition> availableTools,
            List<AgentExecutionStep> previousSteps,
            String priorTaskContext
    ) {
        int stepNumber = previousSteps.size() + 1;
        StringBuilder conversationContext = buildInitialContext(
                taskDescription, availableTools, previousSteps, priorTaskContext);

        while (stepNumber <= MAX_STEPS) {

            String systemPrompt = buildSystemPrompt(availableTools);
            String llmResponse = llmGatewayService.call(
                    "AGENT_EXECUTOR", systemPrompt, conversationContext.toString());

            boolean isToolCall = llmResponse.contains(TOOL_CALL_MARKER);

            if (!isToolCall) {
                AgentExecutionStep step = AgentExecutionStep.builder()
                        .jobId(jobId)
                        .taskQueueId(taskQueueId)
                        .stepNumber(stepNumber)
                        .agentThought(llmResponse)
                        .toolCalled(null)
                        .toolResult(null)
                        .createdAt(LocalDateTime.now())
                        .build();
                agentExecutionStepRepository.save(step);
                log.info("Agent produced final answer at step={} jobId={}", stepNumber, jobId);
                return llmResponse;
            }

            String toolName = extractMarkerValue(llmResponse, TOOL_NAME_MARKER);
            String toolInput = extractMarkerValue(llmResponse, TOOL_INPUT_MARKER);

            if (toolName.isEmpty()) {
                toolName = extractFallbackToolName(llmResponse);
            }

            String toolResult;
            try {
                toolResult = toolExecutor.execute(toolName, toolInput, availableTools);
            } catch (Exception e) {
                toolResult = "ERROR: tool execution failed: " + e.getMessage();
                log.warn("Tool execution failed toolName={} jobId={} error={}",
                        toolName, jobId, e.getMessage());
            }

            String toolResultJson = safeJsonEncode(toolResult);

            AgentExecutionStep step = AgentExecutionStep.builder()
                    .jobId(jobId)
                    .taskQueueId(taskQueueId)
                    .stepNumber(stepNumber)
                    .agentThought(llmResponse)
                    .toolCalled(toolName)
                    .toolResult(toolResultJson)
                    .createdAt(LocalDateTime.now())
                    .build();
            agentExecutionStepRepository.save(step);

            conversationContext
                    .append("\n\nAGENT_THOUGHT_").append(stepNumber).append(": ")
                    .append(llmResponse)
                    .append("\n\nTOOL_RESULT_").append(stepNumber).append(": ")
                    .append(toolResult)
                    .append("\n\nNow based on the tool result above, either:")
                    .append("\n- Call another tool if you need more information")
                    .append("\n- Provide your final answer if you have enough information")
                    .append("\n\nDo NOT repeat a TOOL_CALL if you already have the information needed.");

            stepNumber++;
        }

        log.error("Agent exceeded MAX_STEPS={} jobId={}", MAX_STEPS, jobId);
        throw new RuntimeException("Agent exceeded maximum steps for jobId: " + jobId);
    }

    private String safeJsonEncode(String value) {
        try {
            return objectMapper.writeValueAsString(Map.of("result", value));
        } catch (Exception e) {
            return "{\"result\": \"error encoding tool result\"}";
        }
    }

    private String buildSystemPrompt(List<ToolDefinition> availableTools) {
        StringBuilder sb = new StringBuilder();
        sb.append("You are an AI agent. Complete the given task.\n\n");
        sb.append("To call a tool respond with EXACTLY this format and nothing else:\n");
        sb.append("TOOL_CALL:\n");
        sb.append("TOOL_NAME: <tool_identifier>\n");
        sb.append("TOOL_INPUT: <your search query or input>\n\n");
        sb.append("To give a final answer respond with plain text only.\n");
        sb.append("Do NOT include TOOL_CALL in your final answer.\n\n");
        sb.append("Available tools:\n");
        for (ToolDefinition tool : availableTools) {
            sb.append("- ").append(tool.getToolIdentifier())
              .append(": ").append(tool.getDescription()).append("\n");
        }
        return sb.toString();
    }

    private StringBuilder buildInitialContext(
            String taskDescription,
            List<ToolDefinition> availableTools,
            List<AgentExecutionStep> previousSteps,
            String priorTaskContext
    ) {
        StringBuilder sb = new StringBuilder();
        sb.append("TASK: ").append(taskDescription).append("\n");

        if (priorTaskContext != null && !priorTaskContext.isBlank()) {
            sb.append("\nCONTEXT FROM PREVIOUS TASKS:\n");
            sb.append(priorTaskContext);
            sb.append("\n");
        }

        if (!previousSteps.isEmpty()) {
            sb.append("\nPREVIOUS STEPS (resume from here):\n");
            for (AgentExecutionStep step : previousSteps) {
                sb.append("Step ").append(step.getStepNumber())
                  .append(": ").append(step.getAgentThought()).append("\n");
                if (step.getToolResult() != null) {
                    sb.append("Tool Result: ").append(step.getToolResult()).append("\n");
                }
            }
        }

        return sb;
    }

    private String extractMarkerValue(String response, String marker) {
        int markerIndex = response.indexOf(marker);
        if (markerIndex == -1) return "";
        int valueStart = markerIndex + marker.length();
        int valueEnd = response.indexOf("\n", valueStart);
        if (valueEnd == -1) valueEnd = response.length();
        return response.substring(valueStart, valueEnd).trim();
    }

    private String extractFallbackToolName(String response) {
        if (response.contains("web_search")) return "web_search";
        if (response.contains("fetch_page")) return "fetch_page";
        return "";
    }
}
