BEGIN;

CREATE TABLE agent_registry (
    id                    UUID         NOT NULL DEFAULT gen_random_uuid() PRIMARY KEY,
    agent_type            VARCHAR(100) NOT NULL UNIQUE,
    supported_task_types  TEXT[]       NOT NULL,
    endpoint              VARCHAR(255) NOT NULL,
    is_active             BOOLEAN      NOT NULL DEFAULT true,
    created_at            TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT now(),
    updated_at            TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT now()
);

CREATE INDEX idx_agent_registry_is_active
    ON agent_registry (is_active);

COMMIT;
