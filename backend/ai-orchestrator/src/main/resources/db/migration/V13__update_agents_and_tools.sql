BEGIN;

DELETE FROM agent_registry;
DELETE FROM tool_registry;

INSERT INTO agent_registry (id, agent_type, supported_task_types, endpoint, is_active, created_at, updated_at)
VALUES
(
    gen_random_uuid(),
    'RESEARCH_AGENT',
    ARRAY['RESEARCH_AGENT'],
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

INSERT INTO tool_registry (id, tool_name, description, tool_identifier, input_schema, is_active, created_at)
VALUES
(
    gen_random_uuid(),
    'Web Search',
    'Searches the web for current information on a given query. Returns a list of relevant results with titles, URLs, and content snippets. Use this when you need to find information about any topic.',
    'web_search',
    '{"type": "object", "properties": {"query": {"type": "string", "description": "The search query"}}, "required": ["query"]}',
    true,
    now()
),
(
    gen_random_uuid(),
    'Fetch Page',
    'Fetches the full text content of a web page given its URL. Use this when you need to read the complete content of a specific page found via web search.',
    'fetch_page',
    '{"type": "object", "properties": {"url": {"type": "string", "description": "The full URL of the page to fetch"}}, "required": ["url"]}',
    true,
    now()
);

COMMIT;
