"""Apply the audited attempt-log migration; credentials stay in private local config.

Run with --check first. Stop all applications using this DB before --apply.
The backup includes provider payloads: keep it outside Git and restrict access.
"""
import argparse
import datetime
import json
from pathlib import Path
from urllib.parse import urlparse
import psycopg2

root = Path(__file__).resolve().parents[1]
args = argparse.ArgumentParser()
args.add_argument('--apply', action='store_true')
args.add_argument('--check', action='store_true')
options = args.parse_args()
p = dict(line.strip().split('=', 1) for line in
         (root / 'application-local.properties').read_text(encoding='utf-8').splitlines()
         if '=' in line and not line.lstrip().startswith('#'))
u = urlparse(p['spring.datasource.url'].removeprefix('jdbc:'))
with psycopg2.connect(host=u.hostname, port=u.port or 5432, dbname=u.path.lstrip('/'),
                      user=p['spring.datasource.username'], password=p['spring.datasource.password'],
                      connect_timeout=15) as c:
    with c.cursor() as s:
        s.execute('SET search_path TO public')
        s.execute("SELECT to_regclass('public.llm_run_attempts') IS NOT NULL")
        old_exists = s.fetchone()[0]
        s.execute('SELECT count(*) FROM llm_runs')
        runs = s.fetchone()[0]
        s.execute("SELECT count(*) FROM llm_runs WHERE status='RUNNING'")
        running = s.fetchone()[0]
        s.execute("SELECT count(*) FROM llm_results")
        results = s.fetchone()[0]
        s.execute("SELECT EXISTS (SELECT 1 FROM information_schema.columns WHERE table_schema='public' AND table_name='llm_runs' AND column_name='attempts')")
        new_attempts = None
        if s.fetchone()[0]:
            s.execute('SELECT coalesce(sum(jsonb_array_length(attempts)),0) FROM llm_runs')
            new_attempts = s.fetchone()[0]
        old = []
        if old_exists:
            s.execute('SELECT to_jsonb(a) FROM llm_run_attempts a ORDER BY llm_run_id,attempt_no')
            old = [row[0] for row in s.fetchall()]
        print(json.dumps({'old_table_exists': old_exists, 'runs': runs,
                          'old_attempts': len(old), 'merged_attempts': new_attempts,
                          'running': running, 'results': results}))
        if not options.apply:
            raise SystemExit(0)
        if running:
            raise RuntimeError('Active RUNNING work must finish before migration')
        stamp = datetime.datetime.now(datetime.timezone.utc).strftime('%Y%m%dT%H%M%SZ')
        backup = root / 'docs/db-backups' / ('llm-attempts-' + stamp + '.json')
        backup.parent.mkdir(parents=True, exist_ok=True)
        backup.write_text(json.dumps(old, ensure_ascii=False, indent=2), encoding='utf-8')
        # Commit read-only inventory before the SQL file's own explicit transaction.
        c.commit()
        s.execute((root / 'src/main/resources/db/manual/V20261008_01__merge_llm_attempts_into_runs.sql').read_text(encoding='utf-8'))
        s.execute("SELECT to_regclass('public.llm_run_attempts') IS NULL")
        assert s.fetchone()[0], 'Old table still exists'
        s.execute('SELECT e FROM llm_runs r CROSS JOIN LATERAL jsonb_array_elements(r.attempts) e')
        migrated = {e['id']: e for (e,) in s.fetchall()}
        assert all(migrated.get(e['id']) == e for e in old), 'Attempt contents changed'
        s.execute('SELECT count(*) FROM llm_runs')
        assert s.fetchone()[0] == runs, 'Run count changed'
        s.execute('SELECT count(*) FROM llm_results')
        assert s.fetchone()[0] == results, 'Result count changed'
        print(json.dumps({'verified_old_attempts': len(old), 'total_attempts': len(migrated),
                          'old_table_removed': True, 'backup': str(backup)}))
