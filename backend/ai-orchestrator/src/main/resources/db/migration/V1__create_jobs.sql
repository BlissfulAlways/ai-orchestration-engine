BEGIN;

CREATE TABLE jobs (
    id               UUID         NOT NULL DEFAULT gen_random_uuid() PRIMARY KEY,
    user_id          VARCHAR(255) NOT NULL,
    goal             TEXT         NOT NULL,
    status           VARCHAR(50)  NOT NULL,
    final_answer_id  UUID         NULL,
    created_at       TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT now(),
    updated_at       TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT now()
);

ALTER TABLE jobs
    ADD CONSTRAINT jobs_status_check
    CHECK (status IN (
        'RECEIVED',
        'PLANNING',
        'ROUTING',
        'EXECUTING',
        'AGGREGATING',
        'COMPLETED',
        'FAILED'
    ));

CREATE INDEX idx_jobs_status  ON jobs (status);
CREATE INDEX idx_jobs_user_id ON jobs (user_id);

COMMIT;
