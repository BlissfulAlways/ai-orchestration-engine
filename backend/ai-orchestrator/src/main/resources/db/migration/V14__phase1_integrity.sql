BEGIN;

-- Lets the API tell the user WHY a job failed (see JobController).
ALTER TABLE jobs ADD COLUMN failure_reason TEXT NULL;

-- Remove any existing duplicate results for one task, keeping the earliest.
-- Without this the UNIQUE constraint below would fail on dirty data.
DELETE FROM task_results a
USING task_results b
WHERE a.planned_task_id = b.planned_task_id
  AND (a.created_at, a.id) > (b.created_at, b.id);

-- One planned task can have at most one result. The database now enforces
-- this, so a retry can never create a second result row.
ALTER TABLE task_results
    ADD CONSTRAINT uq_task_results_planned_task UNIQUE (planned_task_id);

COMMIT;