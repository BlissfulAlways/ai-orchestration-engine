BEGIN;

CREATE TABLE planned_tasks (
    id                   UUID         NOT NULL DEFAULT gen_random_uuid() PRIMARY KEY,
    job_id               UUID         NOT NULL REFERENCES jobs(id) ON DELETE CASCADE,
    sequence_number      INTEGER      NOT NULL,
    task_description     TEXT         NOT NULL,
    required_agent_type  VARCHAR(100) NOT NULL,
    created_at           TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT now()
);

CREATE INDEX idx_planned_tasks_job_id
    ON planned_tasks (job_id);

CREATE INDEX idx_planned_tasks_job_id_sequence
    ON planned_tasks (job_id, sequence_number);

COMMIT;
