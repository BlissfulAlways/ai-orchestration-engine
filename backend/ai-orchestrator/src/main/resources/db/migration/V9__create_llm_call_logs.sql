BEGIN;

CREATE TABLE llm_call_logs (
    id                 UUID         NOT NULL DEFAULT gen_random_uuid() PRIMARY KEY,
    calling_component  VARCHAR(100) NOT NULL,
    model_name         VARCHAR(100) NOT NULL,
    prompt             JSONB        NOT NULL,
    response           JSONB        NULL,
    tokens_used        INTEGER      NULL,
    status             VARCHAR(50)  NOT NULL,
    created_at         TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT now()
);

ALTER TABLE llm_call_logs
    ADD CONSTRAINT llm_call_logs_status_check
    CHECK (status IN (
        'SUCCESS',
        'FAILED'
    ));

CREATE INDEX idx_llm_call_logs_component_created
    ON llm_call_logs (calling_component, created_at);

CREATE INDEX idx_llm_call_logs_created_at
    ON llm_call_logs (created_at);

COMMIT;
