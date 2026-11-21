BEGIN;

-- A running task updates this every 30 seconds. A task is only called "stuck" when
-- its heartbeat goes quiet, so a slow but alive task is no longer re-run.
ALTER TABLE task_queue ADD COLUMN heartbeat_at TIMESTAMP WITH TIME ZONE NULL;

-- Tasks already running when this migration is applied get a starting heartbeat.
UPDATE task_queue SET heartbeat_at = picked_up_at WHERE status = 'PROCESSING';

-- New job status: the job finished and has an answer, but some tasks failed.
ALTER TABLE jobs DROP CONSTRAINT jobs_status_check;
ALTER TABLE jobs ADD CONSTRAINT jobs_status_check
    CHECK (status IN ('RECEIVED', 'PLANNING', 'ROUTING', 'EXECUTING', 'AGGREGATING',
                      'COMPLETED', 'COMPLETED_WITH_ERRORS', 'FAILED'));

-- The recovery scheduler looks up jobs by status and age.
CREATE INDEX idx_jobs_status_updated_at ON jobs (status, updated_at);

COMMIT;