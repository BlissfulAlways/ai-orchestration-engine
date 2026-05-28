BEGIN;

CREATE TABLE final_answers (
    id              UUID  NOT NULL DEFAULT gen_random_uuid() PRIMARY KEY,
    job_id          UUID  NOT NULL UNIQUE REFERENCES jobs(id) ON DELETE CASCADE,
    answer_content  TEXT  NOT NULL,
    created_at      TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT now()
);

CREATE INDEX idx_final_answers_job_id
    ON final_answers (job_id);

ALTER TABLE jobs
    ADD CONSTRAINT fk_jobs_final_answer
    FOREIGN KEY (final_answer_id)
    REFERENCES final_answers(id)
    ON DELETE SET NULL;

COMMIT;
