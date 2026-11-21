"""Isolate market backfills while keeping the real Spring ingestion and builder."""
import argparse
import json
import os
import re
import subprocess
import time
import uuid
from contextlib import contextmanager
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
        "companies", "securities", "market_indices", "ingestion_jobs")


def properties():
    return dict(line.strip().split("=", 1) for line in CONFIG.read_text(encoding="utf-8").splitlines()
                if "=" in line and not line.lstrip().startswith(("#", "!")))


def connect(schema="public", readonly=True):
    p = properties()
    url = re.fullmatch(r"jdbc:postgresql://([^:/]+):(\d+)/([^?]+)(?:\?.*)?", p["spring.datasource.url"])
    if not url or not re.fullmatch(r"public|market_stage_[0-9a-f]{24}", schema):
        raise ValueError("INVALID_STAGING_DATABASE_CONFIGURATION")
    c = psycopg2.connect(host=url[1], port=url[2], dbname=url[3], user=p["spring.datasource.username"],
                         password=p["spring.datasource.password"], connect_timeout=8,
                         options=f"-c search_path={schema} -c statement_timeout=120000")
    c.set_session(readonly=readonly)
    return c


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
        q.execute(sql.SQL("SET LOCAL search_path TO {}").format(sql.Identifier(name)))
        q.execute("SELECT current_schema(),count(*) FROM market_prices")
        assert q.fetchone() == (name, 0)
        for table in catalog:
            if table in SEED or table in {"validation_results", "validation_attempts"}:
                continue
            if table.startswith(("validation_rule", "validation_policy")):
                q.execute(sql.SQL("INSERT INTO {} OVERRIDING SYSTEM VALUE SELECT * FROM {}").format(
                    sql.Identifier(name, table), sql.Identifier("public", table)))
    (out / "schema.json").write_text(json.dumps({"schema": name, "tables": len(tables)}, indent=2))
    print(name, flush=True)
    return name


class Backend:
    def __init__(self, port):
        self.port = port
        self.token = os.environ.get("FINANCIAL_ADMIN_API_TOKEN") or properties()["financial.admin.api-token"]

    def call(self, path, body=None, timeout=180):
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
    jar, = sorted((BACKEND / "target").glob("*-ingestion.jar"))
    cmd = ["java", "-Xmx1250m", "-jar", str(jar), f"--server.port={port}", "--server.address=127.0.0.1",
           "--spring.config.additional-location=file:" + str(CONFIG), "--spring.datasource.url=" + jdbc,
           "--spring.jpa.hibernate.ddl-auto=none", "--financial.raw-diagnostics.include-body-enabled=true",
           f"--financial.python-service.base-url=http://127.0.0.1:{upstream}"]
    disabled = ["admin.commands", "admin.schedule", "ingestion.scheduler", "validation.scheduler", "llm.scheduler",
                "macro.scheduler", "security-job-reconciliation.scheduler", "news-recovery.scheduler", "llm"]
    cmd += [f"--financial.{key}.enabled=false" for key in disabled]
    cmd += [f"--financial.{key}.catalog.seed-enabled=false" for key in ("validation", "ingestion", "master-data", "llm")]
    api = Backend(port)
    return service(cmd, port, out / f"spring-{schema}.log", lambda: api.call("/api/health", timeout=3))


def upstream(port, out):
    p = PROVIDER / "provider"
    cmd = [str(PROVIDER / "venv/Scripts/python.exe"), "-m", "uvicorn", "--app-dir", str(p),
           "app.main:app", "--host", "127.0.0.1", "--port", str(port)]
    return service(cmd, port, out / "provider.log", lambda: urlopen(f"http://127.0.0.1:{port}/openapi.json", timeout=2).close())


if __name__ == "__main__":
    parser = argparse.ArgumentParser()
    parser.add_argument("--out", type=Path, required=True)
    args = parser.parse_args()
    stage(args.out)
