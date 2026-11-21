package com.orchestrator.ai_orchestrator.apigateway.domain;

public enum JobStatus {
    RECEIVED,
    PLANNING,
    ROUTING,
    EXECUTING,
    AGGREGATING,
    COMPLETED,
    COMPLETED_WITH_ERRORS,
    FAILED
}
