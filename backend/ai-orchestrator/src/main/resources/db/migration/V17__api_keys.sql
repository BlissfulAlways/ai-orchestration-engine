BEGIN;

-- One row per API key. The raw key is NEVER stored; only its SHA-256 hash is, so a leaked
-- database does not leak working keys.
CREATE TABLE api_keys (
    id          UUID         NOT NULL DEFAULT gen_random_uuid() PRIMARY KEY,
    user_id     VARCHAR(100) NOT NULL,
    label       VARCHAR(100) NOT NULL DEFAULT '',
    key_hash    VARCHAR(64)  NOT NULL UNIQUE,   -- 64 hex characters = SHA-256
    created_at  TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT now(),
    revoked_at  TIMESTAMP WITH TIME ZONE NULL   -- set = the key no longer works
);

CREATE INDEX idx_api_keys_user_id ON api_keys (user_id);

-- "List my jobs, newest first" and the per-user active-job count both filter by user.
CREATE INDEX idx_jobs_user_created ON jobs (user_id, created_at DESC);

COMMIT;