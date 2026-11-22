-- Apply after Java and Admin FE no longer use the old schedule and event endpoints.
BEGIN;
SET LOCAL lock_timeout = '5s';

DO $$
BEGIN
  IF to_regclass('admin_schedule_policy') IS NOT NULL THEN
    LOCK TABLE admin_schedule_policy IN SHARE ROW EXCLUSIVE MODE;
    IF EXISTS (SELECT 1 FROM admin_schedule_policy WHERE enabled OR jsonb_array_length(job_codes) > 0) THEN
      RAISE EXCEPTION 'Move the existing schedule into ingestion_jobs before dropping its old policy';
    END IF;
  END IF;

  IF to_regclass('admin_operation_events') IS NOT NULL THEN
    LOCK TABLE admin_operation_events IN SHARE ROW EXCLUSIVE MODE;
    WITH latest AS (
      SELECT DISTINCT ON (target_id) target_id, reason, principal, created_at
      FROM admin_operation_events
      WHERE event_type = 'ISSUE_REVIEWED' AND target_id IS NOT NULL AND btrim(reason) <> ''
      ORDER BY target_id, created_at DESC, id DESC
    )
    UPDATE validation_results v
    SET resolution_note = latest.reason, resolved_at = latest.created_at,
        resolved_by_user_id = (
          SELECT CASE WHEN count(*) = 1 THEN min(a.user_id::text)::uuid END
          FROM accounts a WHERE a.username = latest.principal
        )
    FROM latest
    WHERE v.id = latest.target_id AND v.result_status = 'FAIL'
      AND (v.resolution_note IS NULL OR btrim(v.resolution_note) = '');
  END IF;
END $$;

DROP TABLE IF EXISTS admin_operation_events, admin_schedule_policy;
COMMIT;
