"""Screen real source windows in staging and publish only matching, approved securities."""
import argparse
import hashlib
import json
import re
import threading
import time
from concurrent.futures import ThreadPoolExecutor, as_completed
from datetime import date, timedelta
from pathlib import Path

from market_staging import Backend, ROOT, connect, spring, upstream

BUILD_LOCK = threading.Lock()


def digest(data):
    return hashlib.sha256(json.dumps(data, sort_keys=True, separators=(",", ":"), allow_nan=False).encode()).hexdigest()


def payload_fingerprint(payload, legacy=False):
    transient = {"retrieved_at", "fetched_at"}
    if not legacy:
        transient.add("elapsed_ms")
    return digest({k: v for k, v in payload.items() if k not in transient})


def save(path, value):
    tmp = path.with_suffix(".tmp")
    tmp.write_text(json.dumps(value, ensure_ascii=False, indent=2, default=str), encoding="utf-8")
    tmp.replace(path)


def market_dates(schema):
    with connect(schema) as c, c.cursor() as q:
        q.execute("""
          SELECT s.symbol,(m.price_timestamp AT TIME ZONE 'Asia/Ho_Chi_Minh')::date
          FROM market_prices m JOIN securities s ON s.id=m.security_id
          JOIN raw_payloads r ON r.id=m.raw_payload_id
          JOIN data_versions v ON v.id=m.data_version_id AND v.status='ACTIVATED'
          WHERE m.is_canonical AND m.interval_code='1d'
          AND m.adjusted_close>0 AND m.price_basis='UNADJUSTED'
          ORDER BY 1,2
        """)
        dates = {}
        for ticker, day in q:
            if day.isoformat() in dates.setdefault(ticker, set()):
                raise ValueError("DUPLICATE_CANONICAL_DATE")
            dates[ticker].add(day.isoformat())
    return dates


def coverage(dates, calendar, first, last):
    available = {d for d in dates if first <= d <= last}
    expected = {d for d in calendar if first <= d <= last}
    fraction = len(available & expected) / len(expected) if expected else 0
    early = (date.fromisoformat(first) + timedelta(days=31)).isoformat()
    recent = (date.fromisoformat(last) - timedelta(days=35)).isoformat()
    return dict(rows=len(available), coverage=fraction, first=min(available, default=None),
                last=max(available, default=None),
                eligible=len(available) >= 1200 and fraction >= .95 and
                         min(available, default="9999") <= early and max(available, default="") >= recent)


def catalog(api):
    return ({r["companyCode"]: r for r in api.call("/api/companies")},
            {r["symbol"]: r for r in api.call("/api/securities?activeOnly=false")},
            {r["code"]: r["id"] for r in api.call("/api/ingestion-jobs?activeOnly=false")})


def ensure_master(api, row, companies, securities, source):
    ticker = row["code"]
    if ticker in securities:
        return
    company = companies.get(ticker)
    if company is None:
        company = api.call("/api/companies", dict(companyCode=ticker, legalName=row["companyName"],
                           shortName=row.get("shortName"), taxCode=row.get("taxCode"),
                           listingStatus="LISTED", description="VNDirect directory provenance: " + source,
                           active=True))
        companies[ticker] = company
    securities[ticker] = api.call("/api/securities", dict(companyId=company["id"], symbol=ticker,
                                  exchange="HOSE", securityType="STOCK", currency="VND", active=True,
                                  listedDate=row["listedDate"], primary=True))


def fetch(api, body):
    source = api.call("/api/ingestions/manual", body)
    if source.get("status") != "SUCCESS" or source.get("upstreamStatus") != 200:
        raise ValueError("SOURCE_NOT_SUCCESSFUL")
    raw = api.call("/api/raw-payloads/" + source["rawPayloadId"] + "?includeBody=true")
    payload = raw["body"]
    if isinstance(payload, str):
        payload = json.loads(payload)
    if not isinstance(payload, dict) or not isinstance(payload.get("data"), list) or not payload["data"]:
        raise ValueError("SOURCE_HAS_NO_ROWS")
    return source, payload_fingerprint(payload), payload["data"]


def build(api, source, job):
    checked = api.call("/api/admin/validation/raw-payloads/" + source["rawPayloadId"], {})
    result = dict(validation=checked)
    if checked.get("status") != "ACCEPTED" or checked.get("failed", 0) or not checked.get("dataVersionId"):
        return result
    path = f"/api/ingestion-jobs/{job}/versions/{checked['dataVersionId']}/build"
    with BUILD_LOCK:
        for delay in (.7, 1.5, 3, 6, None):
            try:
                result["build"] = api.call(path, {})
                break
            except RuntimeError as exc:
                if delay is None or not str(exc).startswith("HTTP 409"):
                    raise
                time.sleep(delay)
    return result


def window(start, end):
    from datetime import date
    a, b = date.fromisoformat(start), date.fromisoformat(end)
    if a.year != b.year:
        return [(max(a, date(y, 1, 1)).isoformat(), min(b, date(y, 12, 31)).isoformat())
                for y in range(a.year, b.year + 1)]
    if (b - a).days < 100:
        return []
    return [(max(a, date(a.year, m, 1)).isoformat(), min(b, date(a.year, n, d)).isoformat())
            for m, n, d in [(1, 3, 31), (4, 6, 30), (7, 9, 30), (10, 12, 31)]
            if date(a.year, m, 1) <= b and date(a.year, n, d) >= a]


def around_bad_prices(rows, start, end):
    """Propose disjoint retries. Each retry still needs full Spring validation."""
    from datetime import date, timedelta
    from decimal import Decimal, InvalidOperation

    def value(row, key):
        src = row.get(key, row.get(key + "_price"))
        return Decimal(str(src))

    a, b = date.fromisoformat(start), date.fromisoformat(end)
    invalid = set()
    for row in rows:
        try:
            day = date.fromisoformat(row["date"])
            low, high = value(row, "low"), value(row, "high")
            if not (0 < low <= value(row, "open") <= high and low <= value(row, "close") <= high):
                invalid.add(day)
        except (InvalidOperation, KeyError, TypeError, ValueError):
            return [], []
    if not 0 < len(invalid) <= 12 or any(day < a or day > b for day in invalid):
        return [], []
    result = []
    left = a
    for day in sorted(invalid):
        if left < day:
            result.append((left.isoformat(), (day - timedelta(days=1)).isoformat()))
        left = day + timedelta(days=1)
    if left <= b:
        result.append((left.isoformat(), b.isoformat()))
    return result, [day.isoformat() for day in sorted(invalid)]


def screen(api, row, job, first, last, folder):
    ticker = row["code"]
    journal = []

    def visit(start, end):
        body = dict(operation="OHLCV", provider="vndirect", dataSourceCode="VNDIRECT", symbol=ticker,
                    startDate=start, endDate=end, interval="1d", parameters={})
        receipt = dict(body=body)
        journal.append(receipt)
        data = []
        try:
            source, fingerprint, data = fetch(api, body)
            receipt.update(source=source, fingerprint=fingerprint)
            receipt.update(build(api, source, job))
        except (RuntimeError, ValueError, OSError) as exc:
            receipt["error"] = str(exc)[:300]
        if receipt.get("validation", {}).get("status") == "REJECTED":
            gaps, dates = around_bad_prices(data, start, end)
            if dates:
                receipt["source_ohlc_reject_dates"] = dates
            save(folder / f"{ticker}.json", journal)
            for a, b in gaps or window(start, end):
                time.sleep(.35)
                visit(a, b)
        else:
            save(folder / f"{ticker}.json", journal)

    visit(first, last)
    return journal


def approved(records):
    return [r for r in records if r.get("build", {}).get("status") in {"SUCCESS", "NO_CHANGES"}]


def repair_build_conflicts(api, folder, job):
    for file in sorted(folder.glob("*.json")):
        receipts = json.loads(file.read_text(encoding="utf-8"))
        dirty = False
        for record in receipts:
            if not record.get("error", "").startswith("HTTP 409") or not record.get("source"):
                continue
            try:
                result = build(api, record["source"], job)
            except (RuntimeError, OSError, ValueError):
                continue
            record["prior_build_error"] = record.pop("error")
            record.update(result)
            dirty = True
        if dirty:
            save(file, receipts)
            print(file.stem, "build-conflict retried", len(approved(receipts)), flush=True)


def staged_hashes(schema, records):
    hashes = {}
    with connect(schema) as c, c.cursor() as q:
        for item in approved(records):
            rid, vid = item["source"]["rawPayloadId"], item["validation"]["dataVersionId"]
            q.execute("""
              SELECT r.payload FROM raw_payloads r JOIN data_versions v ON v.ingestion_run_id=r.ingestion_run_id
              WHERE r.id=%s AND v.id=%s AND v.status='ACTIVATED'
            """, (rid, vid))
            data = q.fetchone()
            if data is None or item["fingerprint"] not in {
                    payload_fingerprint(data[0]), payload_fingerprint(data[0], legacy=True)}:
                raise ValueError("STAGE_AUDIT_CONTENT_MISMATCH")
            hashes[rid] = payload_fingerprint(data[0])
    return hashes


def promote(row, records, api, state, source_ref, schema):
    companies, securities, jobs = state
    # Public receives raw audit only for stage-approved windows. Create the master
    # after every refetched window matches its staged content.
    raw = []
    hashes = staged_hashes(schema, records)
    for item in approved(records):
        payload, fingerprint, _ = fetch(api, item["body"])
        if fingerprint != hashes[item["source"]["rawPayloadId"]]:
            raise ValueError("SOURCE_CHANGED_SINCE_STAGING")
        raw.append((payload, item["source"]["rawPayloadId"]))
    if not raw:
        raise ValueError("NO_APPROVED_WINDOWS")
    ensure_master(api, row, companies, securities, source_ref)
    results = []
    for payload, staged_raw in raw:
        result = build(api, payload, jobs["MARKET_PRICE_BUILD"])
        results.append(dict(source=payload, staged_raw=staged_raw, **result))
        if result.get("build", {}).get("status") not in {"SUCCESS", "NO_CHANGES"}:
            break
    return results


def main():
    p = argparse.ArgumentParser()
    p.add_argument("--out", type=Path, required=True)
    p.add_argument("--limit", type=int, default=8)
    p.add_argument("--workers", type=int, choices=[1, 2, 3, 4], default=2)
    p.add_argument("--stage-port", type=int, default=8229)
    p.add_argument("--public-port", type=int, default=8228)
    p.add_argument("--upstream-port", type=int, default=8230)
    p.add_argument("--promote", action="store_true")
    p.add_argument("--repair-build-conflicts", action="store_true")
    p.add_argument("--target", type=int, default=100)
    p.add_argument("--from-date", type=date.fromisoformat, default=date(2021, 1, 1))
    p.add_argument("--through-date", type=date.fromisoformat, default=date(2026, 11, 19))
    p.add_argument("--directory", type=Path)
    args = p.parse_args()
    if args.limit < 0 or args.target < 1 or args.from_date >= args.through_date:
        p.error("Require limit >= 0, target >= 1 and from-date before through-date")
    folder = args.out / "stock-receipts"
    folder.mkdir(exist_ok=True)
    schema = json.loads((args.out / "schema.json").read_text())["schema"]
    if not re.fullmatch(r"market_stage_[0-9a-f]{24}", schema):
        raise ValueError("REFUSING_NON_STAGING_SCHEMA")
    prior = ROOT / "output/local-ai-review/data-20261119"
    directory_path = args.directory or prior / "hose_universe_raw.json"
    directory = json.loads(directory_path.read_text(encoding="utf-8"))
    data = directory["body"]
    if isinstance(data, str):
        data = json.loads(data)
    if data.get("totalElements") != len(data["data"]):
        raise ValueError("INCOMPLETE_DIRECTORY")
    first, last = args.from_date.isoformat(), args.through_date.isoformat()
    current = market_dates("public")
    # Use observed days with at least two incumbent series as the session reference.
    from collections import Counter
    votes = Counter(d for dates in current.values() for d in dates)
    calendar = {d for d, votes_on_day in votes.items() if votes_on_day >= 2}
    remaining = max(0, args.target - sum(coverage(d, calendar, first, last)["eligible"] for d in current.values()))
    candidates = {r["code"]: r for r in data["data"] if r.get("type") == "STOCK"
                  and r.get("floor") == "HOSE" and r.get("status") == "listed"
                  and r.get("listedDate") and r["listedDate"] <= first
                  and r.get("companyName") and r["code"].isascii() and r["code"].isalpha() and len(r["code"]) == 3}
    preferred = json.loads((prior / "extended-cohort.json").read_text())["symbols"]
    order = list(dict.fromkeys(preferred + sorted(candidates)))
    rows = [candidates[t] for t in order if t in candidates and not current.get(t)
            and not (folder / f"{t}.json").exists()][:args.limit]
    api = Backend(args.stage_port)
    with upstream(args.upstream_port, args.out):
        with spring(schema, args.stage_port, args.upstream_port, args.out):
            companies, securities, jobs = catalog(api)
            for row in rows:
                ensure_master(api, row, companies, securities, str(directory["id"]))
            with ThreadPoolExecutor(max_workers=args.workers) as pool:
                tasks = [pool.submit(screen, api, row, jobs["MARKET_PRICE_BUILD"], first, last, folder) for row in rows]
                for task in as_completed(tasks):
                    receipts = task.result()
                    print(receipts[0]["body"]["symbol"], len(approved(receipts)), len(receipts), flush=True)
            if args.repair_build_conflicts:
                repair_build_conflicts(api, folder, jobs["MARKET_PRICE_BUILD"])
        staged = market_dates(schema)
        screened = {ticker: coverage(days, calendar, first, last) for ticker, days in staged.items()}
        save(args.out / "stock-coverage.json", screened)
        if not args.promote:
            return
        with spring("public", args.public_port, args.upstream_port, args.out):
            public = Backend(args.public_port)
            state = catalog(public)
            ready = [ticker for ticker, count in sorted(screened.items()) if
                     count["eligible"] and ticker in candidates and not current.get(ticker)]

            def publish(ticker):
                records = json.loads((folder / f"{ticker}.json").read_text())
                try:
                    result = promote(candidates[ticker], records, public, state, str(directory["id"]), schema)
                    save(args.out / f"promoted-{ticker}.json", result)
                    print(ticker, "published", len(result), flush=True)
                    return len(result) == len(approved(records)) and all(
                            r.get("build", {}).get("status") in {"SUCCESS", "NO_CHANGES"} for r in result)
                except (RuntimeError, ValueError, OSError) as exc:
                    save(args.out / f"blocked-{ticker}.json", {"error": str(exc)[:300]})
                    print(ticker, "blocked", flush=True)
                    return False

            # Source reads can overlap. build() serializes jobs with the same backend lock.
            with ThreadPoolExecutor(max_workers=args.workers) as pool:
                for ticker_batch in iter(lambda: [ready.pop(0) for _ in range(min(remaining, args.workers, len(ready)))], []):
                    remaining -= sum(pool.map(publish, ticker_batch))
        result = market_dates("public")
        save(args.out / "public-coverage.json", {t: coverage(d, calendar, first, last) for t, d in result.items()})


if __name__ == "__main__":
    main()
