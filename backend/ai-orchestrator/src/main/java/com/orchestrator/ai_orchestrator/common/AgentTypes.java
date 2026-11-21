package com.orchestrator.ai_orchestrator.common;

import java.util.List;

/**
 * The agent types the system can run. One list, used by the planner to validate the
 * model's output. The executor still has its own copy; Phase 3 makes it use this one too.
 */
public final class AgentTypes {

    public static final String RESEARCH_AGENT = "RESEARCH_AGENT";
    public static final String WRITER_AGENT = "WRITER_AGENT";

    public static final List<String> ALL = List.of(RESEARCH_AGENT, WRITER_AGENT);

    private AgentTypes() {
        // Constants only.
    }
}