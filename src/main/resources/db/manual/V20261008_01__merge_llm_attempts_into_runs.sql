-- Stop application workers before applying; deploy matching Java code before restarting.
-- Historical migrations remain unchanged. This migration is atomic and rerunnable.
BEGIN;
SET LOCAL lock_timeout = '10s';
ALTER TABLE llm_runs ADD COLUMN IF NOT EXISTS attempts jsonb NOT NULL DEFAULT '[]'::jsonb;
DO $merge$
BEGIN
    IF NOT EXISTS (SELECT 1 FROM pg_constraint WHERE conrelid='llm_runs'::regclass
                   AND conname='ck_llm_runs_attempts_array') THEN
        ALTER TABLE llm_runs ADD CONSTRAINT ck_llm_runs_attempts_array
            CHECK (jsonb_typeof(attempts)='array');
    END IF;
    -- Check the current schema explicitly: isolated tests must never drop public tables.
    IF EXISTS (SELECT 1 FROM pg_class WHERE relnamespace=current_schema()::regnamespace
               AND relname='llm_run_attempts' AND relkind='r') THEN
        LOCK TABLE llm_run_attempts IN ACCESS EXCLUSIVE MODE;
        LOCK TABLE llm_runs IN ACCESS EXCLUSIVE MODE;
        IF EXISTS (SELECT 1 FROM llm_runs WHERE status='RUNNING') THEN
            RAISE EXCEPTION 'Stop/finish RUNNING LLM work before migrating attempt logs';
        END IF;
        IF EXISTS (SELECT 1 FROM llm_run_attempts a JOIN llm_runs r ON r.id=a.llm_run_id
                   CROSS JOIN LATERAL jsonb_array_elements(r.attempts) e
                   WHERE (e->>'attempt_no')::integer=a.attempt_no AND e<>to_jsonb(a)) THEN
            RAISE EXCEPTION 'Conflicting attempt history; nothing has been migrated';
        END IF;
        UPDATE llm_runs r SET attempts=(
            SELECT jsonb_agg(e ORDER BY (e->>'attempt_no')::integer)
            FROM (
                SELECT e FROM jsonb_array_elements(r.attempts) e
                UNION
                SELECT to_jsonb(a) FROM llm_run_attempts a WHERE a.llm_run_id=r.id
            ) combined
        ) WHERE EXISTS (SELECT 1 FROM llm_run_attempts a WHERE a.llm_run_id=r.id);
        IF EXISTS (SELECT 1 FROM llm_run_attempts a WHERE NOT EXISTS (
            SELECT 1 FROM llm_runs r CROSS JOIN LATERAL jsonb_array_elements(r.attempts) e
            WHERE r.id=a.llm_run_id AND e=to_jsonb(a))) THEN
            RAISE EXCEPTION 'Attempt verification failed; original table retained by rollback';
        END IF;
        DROP TABLE llm_run_attempts; -- No CASCADE: unexpected dependencies abort the transaction.
    END IF;
END
$merge$;
COMMIT;
