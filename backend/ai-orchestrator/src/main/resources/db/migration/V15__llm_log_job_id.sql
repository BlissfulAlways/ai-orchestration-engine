BEGIN;

-- Lets us answer "how many tokens did this job cost?" with one query.
-- SET NULL keeps the cost history if a job is ever deleted.
ALTER TABLE llm_call_logs
    ADD COLUMN job_id UUID NULL REFERENCES jobs(id) ON DELETE SET NULL;

-- How long the whole call took, including retries and waits, in milliseconds.
ALTER TABLE llm_call_logs
    ADD COLUMN latency_ms INTEGER NULL;

CREATE INDEX idx_llm_call_logs_job_id ON llm_call_logs (job_id);

COMMIT;