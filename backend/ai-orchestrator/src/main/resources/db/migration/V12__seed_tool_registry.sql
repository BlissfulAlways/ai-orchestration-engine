BEGIN;

INSERT INTO tool_registry (id, tool_name, description, tool_identifier, input_schema, is_active, created_at)
VALUES
(
    gen_random_uuid(),
    'Web Search',
    'Searches the web for information on a given query. Use this when you need current or factual information.',
    'web_search',
    '{"type": "object", "properties": {"query": {"type": "string", "description": "The search query"}}, "required": ["query"]}',
    true,
    now()
),
(
    gen_random_uuid(),
    'Text Summarizer',
    'Summarizes a long piece of text into a concise summary. Use this when you have too much text to process at once.',
    'text_summarizer',
    '{"type": "object", "properties": {"text": {"type": "string", "description": "The text to summarize"}}, "required": ["text"]}',
    true,
    now()
),
(
    gen_random_uuid(),
    'Text Writer',
    'Writes structured content based on given instructions. Use this when you need to produce a written output.',
    'text_writer',
    '{"type": "object", "properties": {"instructions": {"type": "string", "description": "Instructions for what to write"}}, "required": ["instructions"]}',
    true,
    now()
);

COMMIT;
