package com.orchestrator.ai_orchestrator.common;

import java.util.UUID;

/** Anything stored in the database with a UUID primary key. */
public interface Identifiable {

    UUID getId();
}