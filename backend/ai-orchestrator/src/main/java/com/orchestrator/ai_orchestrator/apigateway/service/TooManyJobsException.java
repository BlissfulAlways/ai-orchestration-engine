package com.orchestrator.ai_orchestrator.apigateway.service;

/** A user already has the maximum number of active jobs. The API answers 429. */
public class TooManyJobsException extends RuntimeException {

    public TooManyJobsException(int limit) {
        super("You already have " + limit + " active jobs. Wait for one to finish and try again.");
    }
}