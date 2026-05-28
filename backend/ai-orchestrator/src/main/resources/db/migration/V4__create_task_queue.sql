BEGIN;

CREATE TABLE task_queue (
    id                   UUID        NOT NULL DEFAULT gen_random_uuid() PRIMARY KEY,
    job_id               UUID        NOT NULL REFERENCES jobs(id) ON DELETE CASCADE,
    planned_task_id      UUID        NOT NULL REFERENCES planned_tasks(id) ON DELETE CASCADE,
    required_agent_type  VARCHAR(100) NOT NULL,
    status               VARCHAR(50) NOT NULL DEFAULT 'PENDING',
    retry_count          INTEGER     NOT NULL DEFAULT 0,
    queued_at            TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT now(),
    picked_up_at         TIMESTAMP WITH TIME ZONE NULL
);

ALTER TABLE task_queue
    ADD CONSTRAINT task_queue_status_check
    CHECK (status IN (
        'PENDING',
        'PROCESSING',
        'COMPLETED',
        'FAILED'
    ));

CREATE INDEX idx_task_queue_status_agent_type
    ON task_queue (status, required_agent_type);

CREATE INDEX idx_task_queue_job_id
    ON task_queue (job_id);

COMMIT;
