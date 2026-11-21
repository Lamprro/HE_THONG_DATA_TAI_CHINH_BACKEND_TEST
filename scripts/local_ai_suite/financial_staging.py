"""Staging and promotion pipeline for Financial Statements (BCTC).

Follows the same isolated and verified path as market_staging.py and market_backfill.py:
1. --stage: Creates isolated schema market_stage_[hash], starts python upstream and Spring staging instance.
   Fetches income-statement and balance-sheet via Spring Ingestion API -> raw_payloads -> validation -> exact build.
   Audits quarters, core items (NET_PROFIT_AFTER_TAX, NET_SALES, TOTAL_ASSETS, OWNERS_EQUITY, LIABILITIES).
2. --promote: Promotes to public DB by refetching through Spring production instance, matching fingerprints.

Zero SQL writes to business DB.
"""
import argparse
import hashlib
import json
import os
import re
import statistics
import subprocess
import time
import uuid
from concurrent.futures import ThreadPoolExecutor
from contextlib import contextmanager
from datetime import date
from pathlib import Path
from urllib.error import HTTPError
from urllib.request import Request, urlopen

import psycopg2
from psycopg2 import sql

ROOT = Path(__file__).resolve().parents[2]
BACKEND = Path(os.environ.get("MARKET_STAGE_BACKEND_ROOT") or ROOT.parent / "HE_THONG_DATA_TAI_CHINH_BACKEND_JAVA")
CONFIG = Path(os.environ.get("MARKET_STAGE_CONFIG") or BACKEND / "application-local.properties")
PROVIDER = Path(os.environ.get("MARKET_STAGE_PROVIDER_HOME") or ROOT / "target/local-ai-data-20261119")
SEED = ("data_sources", "validation_rules", "validation_rule_versions",
        "companies", "securities", "market_indices", "ingestion_jobs", "financial_periods")
FINANCIAL_BUILD_JOB = "cee7aae9-26ee-41a9-87e6-0e4e8200022b"


def properties():
    return dict(line.strip().split("=", 1) for line in CONFIG.read_text(encoding="utf-8").splitlines()
                if "=" in line and not line.lstrip().startswith(("#", "!")))


def connect(schema="public", readonly=True):
    p = properties()
    url = re.fullmatch(r"jdbc:postgresql://([^:/]+):(\d+)/([^?]+)(?:\?.*)?", p["spring.datasource.url"])
    if not url or not re.fullmatch(r"public|market_stage_[0-9a-f]{24}", schema):
        raise ValueError(f"INVALID_STAGING_DATABASE_CONFIGURATION: {schema}")
    c = psycopg2.connect(host=url[1], port=url[2], dbname=url[3], user=p["spring.datasource.username"],
                         password=p["spring.datasource.password"], connect_timeout=8,
                         options=f"-c search_path={schema} -c statement_timeout=180000")
    c.set_session(readonly=readonly)
    return c


def create_training_view(cursor, schema):
    cursor.execute(sql.SQL("""
        CREATE OR REPLACE VIEW {}.vw_training_statement_inputs_v1 AS
        SELECT s.id statement_id,s.company_id,s.security_id,se.symbol,s.statement_type,s.report_scope,s.period_basis,
         p.start_date,p.end_date,p.period_type,i.id item_id,i.item_code,i.value,s.currency,s.unit_scale,
         s.published_at source_published_at,coalesce(r.fetched_at,s.created_at) system_first_seen_at,
         CASE WHEN s.metadata_provenance->>'contract'='financial.metadata.v1' AND s.published_at IS NOT NULL
               AND s.is_restated=false AND s.metadata_provenance->'source'->>'revision_status'='ORIGINAL'
              THEN s.published_at+CASE WHEN s.metadata_provenance->'source'->>'publication_precision'='DAY' THEN interval '1 day' ELSE interval '0' END
              ELSE coalesce(r.fetched_at,s.created_at) END known_at,
         coalesce(s.metadata_provenance->>'contract','LEGACY_UNVERIFIED') metadata_contract,
         (s.metadata_provenance->>'contract'='financial.metadata.v1' AND s.published_at IS NOT NULL
          AND s.report_scope IN ('CONSOLIDATED','SEPARATE') AND s.period_basis<>'UNKNOWN'
          AND s.currency IS NOT NULL AND s.unit_scale>0 AND s.is_restated=false
          AND s.metadata_provenance->'source'->>'revision_status'='ORIGINAL') IS TRUE AS strict_point_in_time_eligible,
         s.raw_payload_id,s.data_version_id,s.metadata_provenance
        FROM {}.financial_statements s JOIN {}.financial_statement_items i ON i.financial_statement_id=s.id
        JOIN {}.financial_periods p ON p.id=s.financial_period_id JOIN {}.securities se ON se.id=s.security_id
        JOIN {}.raw_payloads r ON r.id=s.raw_payload_id JOIN {}.data_versions v ON v.id=s.data_version_id
        WHERE s.is_current AND s.is_canonical AND v.status='ACTIVATED' AND v.ingestion_run_id=r.ingestion_run_id;
    """).format(
        sql.Identifier(schema),
        sql.Identifier(schema), sql.Identifier(schema),
        sql.Identifier(schema), sql.Identifier(schema),
        sql.Identifier(schema), sql.Identifier(schema)
    ))


def stage(out):
    out.mkdir(parents=True, exist_ok=False)
    name = "market_stage_" + uuid.uuid4().hex[:24]
    with connect(readonly=False) as c, c.cursor() as q:
        q.execute("SELECT tablename FROM pg_tables WHERE schemaname='public' ORDER BY tablename")
        tables = [r[0] for r in q.fetchall()]
        q.execute(sql.SQL("CREATE SCHEMA {}").format(sql.Identifier(name)))
        for table in tables:
            q.execute(sql.SQL("CREATE TABLE {} (LIKE {} INCLUDING ALL)").format(
                sql.Identifier(name, table), sql.Identifier("public", table)))
        for table in SEED:
            if table not in tables:
                continue
            q.execute(sql.SQL("INSERT INTO {} OVERRIDING SYSTEM VALUE SELECT * FROM {}").format(
                sql.Identifier(name, table), sql.Identifier("public", table)))
        q.execute("SELECT table_name,column_name FROM information_schema.columns WHERE table_schema=%s AND column_default LIKE 'nextval%%'", (name,))
        for table, column in q.fetchall():
            sequence = name + ".stg_" + uuid.uuid5(uuid.NAMESPACE_OID, table + "." + column).hex
            q.execute(sql.SQL("CREATE SEQUENCE {} START 1000000").format(
                sql.Identifier(*sequence.split("."))))
            q.execute(sql.SQL("ALTER TABLE {} ALTER COLUMN {} SET DEFAULT nextval({}::regclass)").format(
                sql.Identifier(name, table), sql.Identifier(column), sql.Literal(sequence)))
        q.execute("SELECT table_name,column_name FROM information_schema.columns WHERE table_schema=%s AND is_identity='YES'", (name,))
        for table, column in q.fetchall():
            q.execute(sql.SQL("SELECT coalesce(max({}),0)+1 FROM {}").format(
                sql.Identifier(column), sql.Identifier(name, table)))
            next_id = q.fetchone()[0]
            q.execute(sql.SQL("ALTER TABLE {} ALTER COLUMN {} RESTART WITH {}").format(
                sql.Identifier(name, table), sql.Identifier(column), sql.Literal(next_id)))
        q.execute("SELECT tablename FROM pg_tables WHERE schemaname='public' AND (tablename LIKE 'validation%%' OR tablename LIKE '%%rule%%')")
        catalog = [r[0] for r in q.fetchall()]
        for table in catalog:
            if table in SEED or table in {"validation_results", "validation_attempts"}:
                continue
            if table.startswith(("validation_rule", "validation_policy")):
                q.execute(sql.SQL("INSERT INTO {} OVERRIDING SYSTEM VALUE SELECT * FROM {}").format(
                    sql.Identifier(name, table), sql.Identifier("public", table)))
        create_training_view(q, name)
        q.execute(sql.SQL("SET LOCAL search_path TO {}").format(sql.Identifier(name)))
        q.execute("SELECT current_schema(), count(*) FROM financial_statements")
        assert q.fetchone() == (name, 0)
    (out / "schema.json").write_text(json.dumps({"schema": name, "tables": len(tables)}, indent=2))
    print(name, flush=True)
    return name


class Backend:
    def __init__(self, port):
        self.port = port
        self.token = os.environ.get("FINANCIAL_ADMIN_API_TOKEN") or properties()["financial.admin.api-token"]

    def call(self, path, body=None, timeout=600):
        request = Request(f"http://127.0.0.1:{self.port}" + path,
                          data=None if body is None else json.dumps(body).encode(),
                          headers={"Authorization": "Bearer " + self.token, "Content-Type": "application/json"})
        try:
            with urlopen(request, timeout=timeout) as response:
                return json.load(response)
        except HTTPError as exc:
            raise RuntimeError(f"HTTP {exc.code} {exc.read(600).decode(errors='replace')}") from None


@contextmanager
def service(command, port, log, ready):
    import socket

    with socket.socket() as sock:
        sock.bind(("127.0.0.1", port))
    with log.open("ab") as stream:
        proc = subprocess.Popen(command, stdout=stream, stderr=stream, creationflags=subprocess.CREATE_NO_WINDOW)
        try:
            for _ in range(75):
                if proc.poll() is not None:
                    raise RuntimeError(f"SERVICE_EXITED: {log}")
                try:
                    ready()
                    break
                except (OSError, RuntimeError):
                    time.sleep(1)
            else:
                raise RuntimeError(f"SERVICE_START_TIMEOUT: {log}")
            yield
        finally:
            if proc.poll() is None:
                proc.terminate()
                try:
                    proc.wait(timeout=15)
                except subprocess.TimeoutExpired:
                    proc.kill()
                    proc.wait()


def spring(schema, port, upstream, out):
    jdbc = properties()["spring.datasource.url"]
    if "currentSchema=" in jdbc:
        jdbc = re.sub(r"currentSchema=[^&]+", "currentSchema=" + schema, jdbc)
    else:
        jdbc += ("&" if "?" in jdbc else "?") + "currentSchema=" + schema
    if not re.fullmatch(r"public|market_stage_[0-9a-f]{24}", schema):
        raise ValueError("INVALID_SCHEMA")
    with connect(schema) as c, c.cursor() as q:
        q.execute("SELECT current_schema()")
        if q.fetchone()[0] != schema:
            raise ValueError("SCHEMA_ISOLATION_FAILED")
    jdbc += "&socketTimeout=600&connectTimeout=60&tcpKeepAlive=true"
    jar, = sorted((BACKEND / "target").glob("*-ingestion.jar"))
    cmd = ["java", "-Xmx1250m", "-jar", str(jar), f"--server.port={port}", "--server.address=127.0.0.1",
           "--spring.config.additional-location=file:" + str(CONFIG), "--spring.datasource.url=" + jdbc,
           "--spring.datasource.hikari.connection-timeout=30000",
           "--spring.datasource.hikari.maximum-pool-size=10",
           "--spring.datasource.hikari.keepalive-time=30000",
           "--spring.datasource.hikari.validation-timeout=5000",
           "--spring.jpa.hibernate.ddl-auto=none", "--financial.raw-diagnostics.include-body-enabled=true",
           f"--financial.python-service.base-url=http://127.0.0.1:{upstream}"]
    disabled = ["admin.commands", "admin.schedule", "ingestion.scheduler", "validation.scheduler", "llm.scheduler",
                "macro.scheduler", "security-job-reconciliation.scheduler", "news-recovery.scheduler", "llm"]
    for d in disabled:
        cmd.append(f"--financial.{d}.enabled=false")
    return service(cmd, port, out / f"spring-{schema}.log",
                   lambda: Backend(port).call("/api/health", timeout=5))


def upstream(port, out):
    p = PROVIDER / "provider"
    python = PROVIDER / ("venv/Scripts/python.exe" if os.name == "nt" else "venv/bin/python")
    cmd = [str(python), "-m", "uvicorn", "--app-dir", str(p),
           "app.main:app", "--host", "127.0.0.1", "--port", str(port)]
    return service(cmd, port, out / "python-upstream.log",
                   lambda: urlopen(f"http://127.0.0.1:{port}/openapi.json", timeout=3).close())


def payload_fingerprint(payload):
    clean = {k: v for k, v in payload.items() if k not in {"elapsed_ms", "retrieved_at", "financial_metadata"}}
    if isinstance(clean.get("data"), list):
        clean["data"] = sorted(
            clean["data"],
            key=lambda x: json.dumps(x, sort_keys=True) if isinstance(x, dict) else str(x)
        )
    body = json.dumps(clean, sort_keys=True, separators=(",", ":")).encode("utf-8")
    return hashlib.sha256(body).hexdigest()


def save(path, value):
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(json.dumps(value, indent=2, default=str), encoding="utf-8")


def fetch_statement(api, symbol, statement):
    body = {
        "operation": "FINANCIAL_STATEMENT",
        "provider": "vndirect",
        "dataSourceCode": "VNDIRECT",
        "symbol": symbol,
        "parameters": {"statement": statement}
    }
    source = api.call("/api/ingestions/manual", body)
    if source.get("status") != "SUCCESS" or source.get("upstreamStatus") != 200:
        raise ValueError(f"SOURCE_FETCH_FAILED: {source}")
    raw = api.call("/api/raw-payloads/" + source["rawPayloadId"] + "?includeBody=true")
    payload = raw["body"]
    if isinstance(payload, str):
        payload = json.loads(payload)
    if not isinstance(payload, dict) or not isinstance(payload.get("data"), list) or not payload["data"]:
        raise ValueError(f"SOURCE_HAS_NO_ROWS: {symbol} {statement}")
    return source, payload_fingerprint(payload), len(payload["data"])


def build_statement(api, raw_payload_id):
    checked = api.call("/api/admin/validation/raw-payloads/" + raw_payload_id, {})
    result = dict(validation=checked)
    if checked.get("status") != "ACCEPTED" or checked.get("failed", 0) or not checked.get("dataVersionId"):
        return result
    path = f"/api/ingestion-jobs/{FINANCIAL_BUILD_JOB}/versions/{checked['dataVersionId']}/build"
    for delay in (2.0, 5.0, 10.0, 15.0, 20.0, 30.0, 45.0, 60.0, None):
        try:
            result["build"] = api.call(path, {})
            break
        except RuntimeError as exc:
            if delay is None or not str(exc).startswith("HTTP 409"):
                raise
            time.sleep(delay)
    return result


def audit_ticker(schema, ticker):
    with connect(schema) as c, c.cursor() as q:
        q.execute("""
            SELECT s.statement_type, count(DISTINCT p.end_date), min(p.end_date), max(p.end_date),
                   count(DISTINCT i.item_code)
            FROM financial_statements s
            JOIN securities se ON se.id = s.security_id
            JOIN financial_periods p ON p.id = s.financial_period_id
            LEFT JOIN financial_statement_items i ON i.financial_statement_id = s.id
            WHERE se.symbol = %s AND s.is_current AND s.is_canonical
            GROUP BY s.statement_type
        """, (ticker,))
        summary = {r[0]: dict(quarters=r[1], first=str(r[2]), last=str(r[3]), items=r[4]) for r in q.fetchall()}

        q.execute("""
            SELECT item_code, count(*)
            FROM vw_training_statement_inputs_v1
            WHERE symbol = %s
            GROUP BY item_code
        """, (ticker,))
        items = dict(q.fetchall())
        return dict(symbol=ticker, statements=summary, core_items=items)


def stage_ticker(api, ticker, folder):
    receipt = dict(symbol=ticker, statements={})
    for st in ("income-statement", "balance-sheet"):
        last_err = None
        for attempt in range(1, 4):
            try:
                source, fp, count = fetch_statement(api, ticker, st)
                build_res = build_statement(api, source["rawPayloadId"])
                if build_res.get("build", {}).get("status") != "SUCCESS" and attempt < 3:
                    time.sleep(2)
                    continue
                receipt["statements"][st] = dict(
                    source=source,
                    fingerprint=fp,
                    raw_count=count,
                    build_result=build_res
                )
                last_err = None
                break
            except Exception as exc:
                last_err = exc
                if attempt < 3:
                    time.sleep(3)
        if last_err is not None:
            raise last_err
    save(folder / f"{ticker}.json", receipt)
    return receipt


def clear_staged_locks(schema):
    with connect(schema, readonly=False) as c, c.cursor() as q:
        q.execute(sql.SQL(
            "UPDATE {}.ingestion_runs SET status = 'FAILED', finished_at = NOW() "
            "WHERE ingestion_job_id = %s AND status = 'RUNNING'"
        ).format(sql.Identifier(schema)), (FINANCIAL_BUILD_JOB,))


def run_stage(args, symbols, schema, folder, api):
    clear_staged_locks(schema)
    results = {}
    total = len(symbols)
    for idx, t in enumerate(symbols, 1):
        receipt_file = folder / f"{t}.json"
        audit_file = folder / f"audit-{t}.json"
        if receipt_file.exists() and audit_file.exists():
            try:
                audit = json.loads(audit_file.read_text(encoding="utf-8"))
                results[t] = dict(status="STAGED", audit=audit, cached=True)
                print(f"[STAGE {idx}/{total}] {t}: CACHED (already staged)", flush=True)
                continue
            except Exception:
                pass
        try:
            res = stage_ticker(api, t, folder)
            audit = audit_ticker(schema, t)
            save(folder / f"audit-{t}.json", audit)
            results[t] = dict(status="STAGED", audit=audit)
            print(f"[STAGE {idx}/{total}] {t}: income={audit['statements'].get('INCOME_STATEMENT', {}).get('quarters', 0)}Q, "
                  f"bs={audit['statements'].get('BALANCE_SHEET', {}).get('quarters', 0)}Q, items={audit['core_items']}", flush=True)
        except Exception as exc:
            results[t] = dict(status="ERROR", error=str(exc))
            print(f"[STAGE {idx}/{total}] {t}: FAILED - {exc}", flush=True)
        save(args.out / "stage-summary.json", results)
    return results


def run_promote(args, symbols, folder, public_api):
    results = {}
    total = len(symbols)
    for idx, t in enumerate(symbols, 1):
        promoted_file = args.out / f"promoted-{t}.json"
        if promoted_file.exists():
            try:
                audit = audit_ticker("public", t)
                results[t] = dict(status="PROMOTED", audit=audit, cached=True)
                print(f"[PROMOTE {idx}/{total}] {t}: CACHED (already promoted)", flush=True)
                continue
            except Exception:
                pass
        receipt_file = folder / f"{t}.json"
        if not receipt_file.exists():
            print(f"[PROMOTE {idx}/{total}] {t}: SKIPPED (no staging receipt)", flush=True)
            continue
        staged = json.loads(receipt_file.read_text(encoding="utf-8"))
        try:
            promoted_steps = {}
            for st in ("income-statement", "balance-sheet"):
                step = staged["statements"][st]
                expected_fp = step["fingerprint"]
                last_err = None
                for attempt in range(1, 4):
                    try:
                        source, fp, count = fetch_statement(public_api, t, st)
                        if fp != expected_fp:
                            raise ValueError(f"FINGERPRINT_MISMATCH for {t} {st}: {fp} vs staged {expected_fp}")
                        build_res = build_statement(public_api, source["rawPayloadId"])
                        if build_res.get("build", {}).get("status") != "SUCCESS" and attempt < 3:
                            time.sleep(2)
                            continue
                        promoted_steps[st] = dict(source=source, fingerprint=fp, count=count, build_result=build_res)
                        last_err = None
                        break
                    except Exception as exc:
                        last_err = exc
                        if attempt < 3:
                            time.sleep(3)
                if last_err is not None:
                    raise last_err
            audit = audit_ticker("public", t)
            save(args.out / f"promoted-{t}.json", dict(symbol=t, steps=promoted_steps, audit=audit))
            results[t] = dict(status="PROMOTED", audit=audit)
            print(f"[PROMOTE {idx}/{total}] {t}: PROMOTED - income={audit['statements'].get('INCOME_STATEMENT', {}).get('quarters', 0)}Q, "
                  f"bs={audit['statements'].get('BALANCE_SHEET', {}).get('quarters', 0)}Q, items={audit['core_items']}", flush=True)
        except Exception as exc:
            results[t] = dict(status="FAILED", error=str(exc))
            print(f"[PROMOTE {idx}/{total}] {t}: FAILED - {exc}", flush=True)
        save(args.out / "promote-summary.json", results)
    return results


def main():
    p = argparse.ArgumentParser()
    p.add_argument("--out", type=Path, required=True)
    p.add_argument("--stage", action="store_true")
    p.add_argument("--promote", action="store_true")
    p.add_argument("--symbols", nargs="+")
    p.add_argument("--stage-port", type=int, default=8249)
    p.add_argument("--public-port", type=int, default=8248)
    p.add_argument("--upstream-port", type=int, default=8250)
    args = p.parse_args()
    if args.stage == args.promote:
        p.error("Choose exactly one of --stage or --promote")

    folder = args.out / "bctc-receipts"
    if args.stage:
        if not (args.out / "schema.json").exists():
            stage(args.out)
    schema = json.loads((args.out / "schema.json").read_text())["schema"]
    folder.mkdir(parents=True, exist_ok=True)

    symbols = args.symbols or ["ACC", "BCM"]

    with upstream(args.upstream_port, args.out):
        if args.stage:
            with spring(schema, args.stage_port, args.upstream_port, args.out):
                run_stage(args, symbols, schema, folder, Backend(args.stage_port))
        elif args.promote:
            with spring("public", args.public_port, args.upstream_port, args.out):
                run_promote(args, symbols, folder, Backend(args.public_port))


if __name__ == "__main__":
    main()
