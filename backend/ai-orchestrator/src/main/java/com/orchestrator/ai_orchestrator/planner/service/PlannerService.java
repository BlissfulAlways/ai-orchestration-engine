package com.orchestrator.ai_orchestrator.planner.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.orchestrator.ai_orchestrator.observer.service.ObserverService;
import com.orchestrator.ai_orchestrator.planner.domain.PlannedTask;
import com.orchestrator.ai_orchestrator.planner.infrastructure.PlannedTaskRepository;
import com.orchestrator.ai_orchestrator.llmgateway.service.LlmGatewayService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Slf4j
@Service
@RequiredArgsConstructor
public class PlannerService {

    private final PlannedTaskRepository plannedTaskRepository;
    private final LlmGatewayService llmGatewayService;
    private final ObserverService observerService;
    private final ObjectMapper objectMapper;

    @Transactional
    public List<PlannedTask> plan(UUID jobId, String goal) {
        try {
            observerService.emit("TASK_PLANNER", "PLANNING_STARTED", jobId,
                    Map.of("jobId", jobId.toString()));

            String systemPrompt = """
                    You are a task planning AI. Break the given goal into a list of sub-tasks.
                    Respond ONLY with a valid JSON array where each element is an object with exactly two fields:
                    "taskDescription": a String describing what to do,
                    "requiredAgentType": one of these exact values: WEB_SEARCH_AGENT, SUMMARIZER_AGENT, WRITER_AGENT.
                    No explanation. No markdown. Only the raw JSON array.
                    """;

            String userPrompt = "Break this goal into sub-tasks: " + goal;

            String rawResponse = llmGatewayService.call("TASK_PLANNER", systemPrompt, userPrompt);

            String cleanedResponse = rawResponse.trim();
            if (cleanedResponse.startsWith("```")) {
                cleanedResponse = cleanedResponse
                        .replaceAll("```json", "")
                        .replaceAll("```", "")
                        .trim();
            }

            JsonNode tasksArray = objectMapper.readTree(cleanedResponse);

            List<PlannedTask> savedTasks = new ArrayList<>();
            int sequenceNumber = 1;

            for (JsonNode taskNode : tasksArray) {
                String taskDescription = taskNode.get("taskDescription").asText();
                String requiredAgentType = taskNode.get("requiredAgentType").asText();

                PlannedTask task = PlannedTask.builder()
                        .jobId(jobId)
                        .sequenceNumber(sequenceNumber++)
                        .taskDescription(taskDescription)
                        .requiredAgentType(requiredAgentType)
                        .createdAt(LocalDateTime.now())
                        .build();

                savedTasks.add(plannedTaskRepository.save(task));
            }

            observerService.emit("TASK_PLANNER", "PLANNING_COMPLETED", jobId,
                    Map.of("jobId", jobId.toString(), "taskCount", savedTasks.size()));

            return savedTasks;

        } catch (Exception e) {
            log.error("PlannerService failed for jobId={} error={}", jobId, e.getMessage());
            observerService.emit("TASK_PLANNER", "PLANNING_FAILED", jobId,
                    Map.of("jobId", jobId.toString(), "error", e.getMessage()));
            throw new RuntimeException("Planning failed for jobId: " + jobId, e);
        }
    }
}
