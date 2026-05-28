BEGIN;

CREATE TABLE tool_registry (
    id               UUID         NOT NULL DEFAULT gen_random_uuid() PRIMARY KEY,
    tool_name        VARCHAR(100) NOT NULL UNIQUE,
    description      TEXT         NOT NULL,
    tool_identifier  VARCHAR(100) NOT NULL UNIQUE,
    input_schema     JSONB        NOT NULL,
    is_active        BOOLEAN      NOT NULL DEFAULT true,
    created_at       TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT now()
);

CREATE INDEX idx_tool_registry_is_active
    ON tool_registry (is_active);

COMMIT;
