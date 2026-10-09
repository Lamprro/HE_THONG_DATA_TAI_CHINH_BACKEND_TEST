"""Acceptance through Java HTTP jobs only. DB access below is read-only; no independent fetcher or data writer."""
from pathlib import Path
from urllib.request import Request, urlopen
from urllib.parse import urlparse
from decimal import Decimal
import argparse
import datetime
import json
import psycopg2

parser = argparse.ArgumentParser()
parser.add_argument("--base-url", default="http://127.0.0.1:8181")
parser.add_argument("--config", default="../application-local.properties")
args = parser.parse_args()
base = args.base_url.rstrip("/")


def http(path, method="GET"):
    request = Request(base + path, data=b"" if method == "POST" else None, method=method)
    with urlopen(request, timeout=120) as response:
        return json.load(response)


evidence = {"checked_at": datetime.datetime.now(datetime.timezone.utc).isoformat(),
            "java_base": base, "writes": "Java ingestion job -> Python deployed API -> raw validation -> macro build"}
evidence["seed"] = http("/api/admin/macro/jobs/seed", "POST")
jobs = [j for j in http("/api/ingestion-jobs") if j["code"] in
        {"MACRO_VN_WORLDBANK_QUARTERLY", "MACRO_VN_BIS_QUARTERLY"}]
assert len(jobs) == 2, "Expected exactly the two Vietnam macro jobs"
evidence["runs"] = []
for round_number in (1, 2):
    for job in jobs:
        result = http("/api/ingestion-jobs/" + job["id"] + "/run", "POST")
        assert result["status"] in ("SUCCESS", "NO_CHANGE"), result
        if round_number == 2:
            assert result["status"] == "NO_CHANGE", "Immediate replay should not change business observations"
        evidence["runs"].append(dict(round=round_number, job=job["code"], result=result))
evidence["coverage"] = http("/api/admin/macro/coverage")

config = dict(line.strip().split("=", 1) for line in Path(args.config).read_text(encoding="utf-8").splitlines()
              if "=" in line and not line.lstrip().startswith("#"))
url = urlparse(config["spring.datasource.url"].removeprefix("jdbc:"))
with psycopg2.connect(host=url.hostname, port=url.port or 5432, dbname=url.path.lstrip("/"),
                     user=config["spring.datasource.username"], password=config["spring.datasource.password"],
                     options="-c default_transaction_read_only=on -c statement_timeout=20000") as connection:
    with connection.cursor() as cursor:
        queries = {
            "counts": "SELECT (SELECT count(*) FROM macro_series),(SELECT count(*) FROM macro_observations)",
            "duplicate_keys": "SELECT count(*) FROM (SELECT macro_series_id,observation_date FROM macro_observations GROUP BY 1,2 HAVING count(*)>1) x",
            "wrong_country": "SELECT count(*) FROM macro_series WHERE country_code IS DISTINCT FROM 'VNM'",
            "broken_provenance": "SELECT count(*) FROM macro_observations o LEFT JOIN raw_payloads r ON r.id=o.raw_payload_id LEFT JOIN data_versions v ON v.id=o.data_version_id WHERE r.id IS NULL OR v.id IS NULL OR r.entity_type<>'MACRO_OBSERVATIONS' OR v.data_domain<>'MACRO' OR v.status<>'ACTIVATED'",
            "versions": "SELECT status,count(*) FROM data_versions WHERE data_domain='MACRO' GROUP BY status ORDER BY status",
            "validation": "SELECT rule_code,result_status,count(*) FROM validation_results WHERE raw_payload_id IN (SELECT id FROM raw_payloads WHERE entity_type='MACRO_OBSERVATIONS') GROUP BY 1,2 ORDER BY 1,2",
            "unrelated_counts": "SELECT (SELECT count(*) FROM financial_statements),(SELECT count(*) FROM market_prices),(SELECT count(*) FROM news_articles),(SELECT count(*) FROM llm_runs)",
        }
        for name, sql in queries.items():
            cursor.execute(sql)
            evidence[name] = cursor.fetchall()
        for name in ("duplicate_keys", "wrong_country", "broken_provenance"):
            assert evidence[name] == [(0,)], (name, evidence[name])
        cursor.execute("SELECT s.code,o.observation_date,o.value,r.payload::text FROM macro_observations o JOIN macro_series s ON s.id=o.macro_series_id JOIN raw_payloads r ON r.id=o.raw_payload_id")
        matched = 0
        for code, period_end, value, raw_text in cursor.fetchall():
            raw = json.loads(raw_text, parse_float=Decimal)
            values = [o["value"] for s in raw["data"] if s["code"] == code for o in s["observations"]
                      if o["observation_date"] == period_end.isoformat()]
            assert len(values) == 1 and value == Decimal(str(values[0])), (code, period_end)
            matched += 1
        evidence["exact_values_matched_to_raw"] = matched

output = Path("target/macro-release-20261009/live-acceptance.json")
output.parent.mkdir(parents=True, exist_ok=True)
output.write_text(json.dumps(evidence, default=str, ensure_ascii=False, indent=2), encoding="utf-8")
print(json.dumps(evidence, default=str, ensure_ascii=False, indent=2))
