package com.orchestrator.ai_orchestrator.executor.service;

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

    public String runLoop(
            UUID jobId,
            UUID taskQueueId,
            String taskDescription,
            List<ToolDefinition> availableTools,
            List<AgentExecutionStep> previousSteps
    ) {
        int stepNumber = previousSteps.size() + 1;
        StringBuilder conversationContext = buildInitialContext(taskDescription, availableTools, previousSteps);

        while (stepNumber <= MAX_STEPS) {

            String systemPrompt = buildSystemPrompt(availableTools);
            String llmResponse = llmGatewayService.call("AGENT_EXECUTOR", systemPrompt, conversationContext.toString());

            boolean isToolCall = llmResponse.contains(TOOL_CALL_MARKER);

            AgentExecutionStep step = AgentExecutionStep.builder()
                    .jobId(jobId)
                    .taskQueueId(taskQueueId)
                    .stepNumber(stepNumber)
                    .agentThought(llmResponse)
                    .toolCalled(null)
                    .toolResult(null)
                    .createdAt(LocalDateTime.now())
                    .build();

            if (!isToolCall) {
                agentExecutionStepRepository.save(step);
                log.info("Agent produced final answer at step={} jobId={}", stepNumber, jobId);
                return llmResponse;
            }

            String toolName = extractMarkerValue(llmResponse, TOOL_NAME_MARKER);
            String toolInput = extractMarkerValue(llmResponse, TOOL_INPUT_MARKER);

            if (toolName.isEmpty()) {
                log.warn("Agent returned TOOL_CALL but empty tool name, treating as final answer jobId={}", jobId);
                agentExecutionStepRepository.save(step);
                return llmResponse;
            }

            String toolResult;
            try {
                toolResult = toolExecutor.execute(toolName, toolInput, availableTools);
            } catch (Exception e) {
                toolResult = "ERROR: tool execution failed: " + e.getMessage();
                log.warn("Tool execution failed toolName={} jobId={} error={}", toolName, jobId, e.getMessage());
            }

            step.setToolCalled(toolName);
            step.setToolResult("{\"result\": \"" + toolResult.replace("\"", "\\\"") + "\"}");
            agentExecutionStepRepository.save(step);

            conversationContext
                    .append("\nAGENT_THOUGHT: ").append(llmResponse)
                    .append("\nTOOL_RESULT: ").append(toolResult)
                    .append("\nContinue working toward the goal. If you have enough information produce the final answer.");

            stepNumber++;
        }

        log.error("Agent exceeded MAX_STEPS={} jobId={}", MAX_STEPS, jobId);
        throw new RuntimeException("Agent exceeded maximum steps for jobId: " + jobId);
    }

    private String buildSystemPrompt(List<ToolDefinition> availableTools) {
        StringBuilder sb = new StringBuilder();
        sb.append("You are an AI agent. Complete the given task using the available tools if needed.\n");
        sb.append("To call a tool respond with exactly this format:\n");
        sb.append("TOOL_CALL:\n");
        sb.append("TOOL_NAME: <tool_identifier>\n");
        sb.append("TOOL_INPUT: <input text>\n\n");
        sb.append("To give a final answer respond with plain text without TOOL_CALL marker.\n\n");
        sb.append("Available tools:\n");
        for (ToolDefinition tool : availableTools) {
            sb.append("- ").append(tool.getToolIdentifier()).append(": ").append(tool.getDescription()).append("\n");
        }
        return sb.toString();
    }

    private StringBuilder buildInitialContext(
            String taskDescription,
            List<ToolDefinition> availableTools,
            List<AgentExecutionStep> previousSteps
    ) {
        StringBuilder sb = new StringBuilder();
        sb.append("TASK: ").append(taskDescription).append("\n");

        if (!previousSteps.isEmpty()) {
            sb.append("\nPREVIOUS STEPS:\n");
            for (AgentExecutionStep step : previousSteps) {
                sb.append("Step ").append(step.getStepNumber()).append(": ").append(step.getAgentThought()).append("\n");
                if (step.getToolResult() != null) {
                    sb.append("Tool Result: ").append(step.getToolResult()).append("\n");
                }
            }
            sb.append("\nContinue from where you left off.\n");
        }

        return sb;
    }

    private String extractMarkerValue(String response, String marker) {
        int markerIndex = response.indexOf(marker);
        if (markerIndex == -1) return "";
        int valueStart = markerIndex + marker.length();
        while (valueStart < response.length() && response.charAt(valueStart) == '\n') valueStart++;
        int valueEnd = response.indexOf("\n", valueStart);
        if (valueEnd == -1) valueEnd = response.length();
        return response.substring(valueStart, valueEnd).trim();
    }
}
