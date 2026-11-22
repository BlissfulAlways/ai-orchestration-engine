BEGIN;

-- The sequence numbers of the tasks whose results a task needs. Empty = needs nothing.
ALTER TABLE planned_tasks
    ADD COLUMN depends_on INTEGER[] NOT NULL DEFAULT '{}';

-- Jobs planned before this change ran strictly one after another. Give them the equivalent
-- dependency (each task depends on the one before it) so jobs already in progress behave
-- exactly as they did.
UPDATE planned_tasks
   SET depends_on = ARRAY[sequence_number - 1]
 WHERE sequence_number > 1;

COMMIT;