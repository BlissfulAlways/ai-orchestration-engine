BEGIN;

CREATE TABLE agent_execution_steps (
    id               UUID         NOT NULL DEFAULT gen_random_uuid() PRIMARY KEY,
    job_id           UUID         NOT NULL REFERENCES jobs(id) ON DELETE CASCADE,
    task_queue_id    UUID         NOT NULL REFERENCES task_queue(id) ON DELETE CASCADE,
    step_number      INTEGER      NOT NULL,
    agent_thought    TEXT         NOT NULL,
    tool_called      VARCHAR(100) NULL,
    tool_result      JSONB        NULL,
    created_at       TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT now()
);

CREATE INDEX idx_agent_execution_steps_task_queue_id
    ON agent_execution_steps (task_queue_id);

CREATE INDEX idx_agent_execution_steps_job_id
    ON agent_execution_steps (job_id);

COMMIT;
