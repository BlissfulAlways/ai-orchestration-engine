BEGIN;

CREATE TABLE task_results (
    id               UUID        NOT NULL DEFAULT gen_random_uuid() PRIMARY KEY,
    job_id           UUID        NOT NULL REFERENCES jobs(id) ON DELETE CASCADE,
    planned_task_id  UUID        NOT NULL REFERENCES planned_tasks(id) ON DELETE CASCADE,
    status           VARCHAR(50) NOT NULL DEFAULT 'PENDING',
    result_content   TEXT        NULL,
    failure_reason   TEXT        NULL,
    created_at       TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT now(),
    updated_at       TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT now()
);

ALTER TABLE task_results
    ADD CONSTRAINT task_results_status_check
    CHECK (status IN (
        'PENDING',
        'COMPLETED',
        'FAILED'
    ));

CREATE INDEX idx_task_results_job_id
    ON task_results (job_id);

CREATE INDEX idx_task_results_job_id_status
    ON task_results (job_id, status);

COMMIT;
