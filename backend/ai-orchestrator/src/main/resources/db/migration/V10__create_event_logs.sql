BEGIN;

CREATE TABLE event_logs (
    id                UUID         NOT NULL DEFAULT gen_random_uuid() PRIMARY KEY,
    source_component  VARCHAR(100) NOT NULL,
    event_type        VARCHAR(100) NOT NULL,
    job_id            UUID         NULL,
    payload           JSONB        NOT NULL,
    created_at        TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT now()
);

CREATE INDEX idx_event_logs_job_id
    ON event_logs (job_id);

CREATE INDEX idx_event_logs_event_type
    ON event_logs (event_type);

CREATE INDEX idx_event_logs_created_at
    ON event_logs (created_at);

CREATE INDEX idx_event_logs_source_created
    ON event_logs (source_component, created_at);

COMMIT;
