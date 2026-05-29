BEGIN;

INSERT INTO agent_registry (id, agent_type, supported_task_types, endpoint, is_active, created_at, updated_at)
VALUES
(
    gen_random_uuid(),
    'WEB_SEARCH_AGENT',
    ARRAY['WEB_SEARCH_AGENT'],
    'internal',
    true,
    now(),
    now()
),
(
    gen_random_uuid(),
    'SUMMARIZER_AGENT',
    ARRAY['SUMMARIZER_AGENT'],
    'internal',
    true,
    now(),
    now()
),
(
    gen_random_uuid(),
    'WRITER_AGENT',
    ARRAY['WRITER_AGENT'],
    'internal',
    true,
    now(),
    now()
);

COMMIT;
