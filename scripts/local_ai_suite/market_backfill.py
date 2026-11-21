"""Backfill EARLIER daily history for securities that already have public prices.

Same real path as market_stage_batch.py: Spring staging (isolated schema) -> existing Python adapter ->
provider -> raw -> validation -> exact-version build. Only windows that end strictly BEFORE the first
public canonical session are fetched, so the public daily range written is always empty beforehand.

Extra gate versus new-ticker staging: a seam check. A short window starting at the first public
session is also staged (staging only, never promoted) and its adjusted_close must match public on the
same dates. A mismatch means the provider's adjustment vintage changed and the two parts would not join.

  python -B market_backfill.py --out <new dir> --stage [--symbols AAA BBB]     # staging only
  python -B market_backfill.py --out <same dir> --promote                        # WRITES public DB
"""
import argparse
import json
import statistics
from concurrent.futures import ThreadPoolExecutor
from datetime import date, timedelta
from pathlib import Path

from market_stage_batch import approved, build, coverage, fetch, market_dates, save, screen, staged_hashes
from market_staging import Backend, ROOT, connect, spring, stage, upstream

SEAM_DAYS = 45
SEAM_TOLERANCE = .002   # max |staged/public - 1| on overlapping sessions
MIN_SESSIONS = 120      # a backfill shorter than ~6 months adds little and is skipped


def public_first_dates():
    with connect() as c, c.cursor() as q:
        q.execute("""SELECT s.symbol, min((m.price_timestamp AT TIME ZONE 'Asia/Ho_Chi_Minh')::date)
                     FROM market_prices m JOIN securities s ON s.id=m.security_id
                     WHERE m.is_canonical AND m.interval_code='1d' GROUP BY 1""")
        return {t: d for t, d in q.fetchall()}


def adjusted(schema, ticker, start, end):
    with connect(schema) as c, c.cursor() as q:
        q.execute("""SELECT (m.price_timestamp AT TIME ZONE 'Asia/Ho_Chi_Minh')::date, m.adjusted_close
                     FROM market_prices m JOIN securities s ON s.id=m.security_id
                     WHERE s.symbol=%s AND m.is_canonical AND m.interval_code='1d'
                       AND (m.price_timestamp AT TIME ZONE 'Asia/Ho_Chi_Minh')::date BETWEEN %s AND %s""",
                  (ticker, start, end))
        return {d.isoformat(): float(v) for d, v in q.fetchall()}


def plan(args):
    directory = json.loads((ROOT / "output/local-ai-review/data-20261119/hose_universe_raw.json").read_text(encoding="utf-8"))
    body = directory["body"]
    body = json.loads(body) if isinstance(body, str) else body
    listed = {r["code"]: r.get("listedDate") for r in body["data"]}
    first = public_first_dates()
    items = []
    for ticker, first_public in sorted(first.items()):
        if args.symbols and ticker not in args.symbols:
            continue
        start = max(args.from_date, date.fromisoformat(listed[ticker][:10])) if listed.get(ticker) else args.from_date
        end = first_public - timedelta(days=1)
        if (end - start).days < 180:
            continue
        items.append(dict(ticker=ticker, start=start.isoformat(), end=end.isoformat(),
                          first_public=first_public.isoformat(), listed=listed.get(ticker),
                          seam_end=(first_public + timedelta(days=SEAM_DAYS)).isoformat()))
    return items


def run_stage(args, items, schema, folder, api):
    jobs = {r["code"]: r["id"] for r in api.call("/api/ingestion-jobs?activeOnly=false")}
    job = jobs["MARKET_PRICE_BUILD"]

    def one(item):
        t = item["ticker"]
        if (folder / f"{t}.json").exists():
            return t, "skipped-existing-receipt"
        row = {"code": t}
        backfill = screen(api, row, job, item["start"], item["end"], folder)
        # Seam window: staging only, kept in a separate receipt and never promoted.
        seam_folder = folder / "seam"
        seam_folder.mkdir(exist_ok=True)
        screen(api, row, job, item["first_public"], item["seam_end"], seam_folder)
        return t, f"{len(approved(backfill))}/{len(backfill)} approved"

    with ThreadPoolExecutor(max_workers=args.workers) as pool:
        for t, status in pool.map(one, items):
            print(t, status, flush=True)


def evaluate(items, schema, calendar):
    staged = market_dates(schema)
    report = {}
    for item in items:
        t = item["ticker"]
        days = {d for d in staged.get(t, set()) if item["start"] <= d <= item["end"]}
        cov = coverage(days, calendar, item["start"], item["end"])
        # Backfill windows can be shorter than 1,200 sessions: use window-relative edges and a session floor.
        expected = [d for d in calendar if item["start"] <= d <= item["end"]]
        edges = bool(days) and min(days) <= (date.fromisoformat(item["start"]) + timedelta(days=31)).isoformat() \
            and max(days) >= (date.fromisoformat(item["end"]) - timedelta(days=10)).isoformat()
        a = adjusted(schema, t, item["first_public"], item["seam_end"])
        b = adjusted("public", t, item["first_public"], item["seam_end"])
        common = sorted(set(a) & set(b))
        dev = max((abs(a[d] / b[d] - 1) for d in common), default=None)
        seam_ok = len(common) >= 15 and dev is not None and dev <= SEAM_TOLERANCE
        eligible = cov["coverage"] >= .95 and edges and len(days) >= MIN_SESSIONS and seam_ok and len(expected) > 0
        report[t] = dict(item, sessions=len(days), coverage=cov["coverage"], edges=edges, seam_sessions=len(common),
                         seam_max_rel_diff=dev, seam_ok=seam_ok, eligible=eligible,
                         seam_ratio_median=statistics.median(a[d] / b[d] for d in common) if common else None)
    return report


def run_promote(args, report, schema, folder, public):
    jobs = {r["code"]: r["id"] for r in public.call("/api/ingestion-jobs?activeOnly=false")}
    job = jobs["MARKET_PRICE_BUILD"]
    first_now = public_first_dates()
    results = {}
    for t, item in sorted(report.items()):
        if not item["eligible"] or (args.out / f"promoted-{t}.json").exists():
            continue
        if first_now.get(t) is None or first_now[t].isoformat() != item["first_public"]:
            save(args.out / f"blocked-{t}.json", {"error": "PUBLIC_RANGE_CHANGED_SINCE_STAGING"})
            continue
        records = json.loads((folder / f"{t}.json").read_text(encoding="utf-8"))
        try:
            hashes = staged_hashes(schema, records)
            refetched = []
            for rec in approved(records):
                if rec["body"]["endDate"] >= item["first_public"]:
                    raise ValueError("WINDOW_OVERLAPS_PUBLIC_RANGE")
                source, fingerprint, _ = fetch(public, rec["body"])
                if fingerprint != hashes[rec["source"]["rawPayloadId"]]:
                    raise ValueError("SOURCE_CHANGED_SINCE_STAGING")
                refetched.append((source, rec["source"]["rawPayloadId"]))
            out = []
            for source, staged_raw in refetched:
                r = build(public, source, job)
                out.append(dict(source=source, staged_raw=staged_raw, **r))
                if r.get("build", {}).get("status") not in {"SUCCESS", "NO_CHANGES"}:
                    break
            save(args.out / f"promoted-{t}.json", out)
            ok = len(out) == len(refetched) and all(r.get("build", {}).get("status") in {"SUCCESS", "NO_CHANGES"} for r in out)
            results[t] = "published" if ok else "partial"
        except (RuntimeError, ValueError, OSError) as exc:
            save(args.out / f"blocked-{t}.json", {"error": str(exc)[:300]})
            results[t] = "blocked"
        print(t, results[t], flush=True)
    return results


def main():
    p = argparse.ArgumentParser()
    p.add_argument("--out", type=Path, required=True)
    p.add_argument("--stage", action="store_true")
    p.add_argument("--promote", action="store_true")
    p.add_argument("--symbols", nargs="+")
    p.add_argument("--workers", type=int, choices=[1, 2, 3], default=2)
    p.add_argument("--from-date", type=date.fromisoformat, default=date(2016, 1, 1))
    p.add_argument("--stage-port", type=int, default=8229)
    p.add_argument("--public-port", type=int, default=8228)
    p.add_argument("--upstream-port", type=int, default=8230)
    args = p.parse_args()
    if args.stage == args.promote:
        p.error("Choose exactly one of --stage or --promote")
    folder = args.out / "stock-receipts"
    if args.stage:
        if not (args.out / "schema.json").exists():
            stage(args.out)
        items = plan(args)
        save(args.out / ("plan.json" if not args.symbols else "plan-" + "-".join(args.symbols[:3]) + ".json"), items)
    schema = json.loads((args.out / "schema.json").read_text())["schema"]
    folder.mkdir(exist_ok=True)
    from collections import Counter
    votes = Counter(d for days in market_dates("public").values() for d in days)
    calendar = {d for d, n in votes.items() if n >= 2}
    with upstream(args.upstream_port, args.out):
        if args.stage:
            with spring(schema, args.stage_port, args.upstream_port, args.out):
                run_stage(args, items, schema, folder, Backend(args.stage_port))
            report = evaluate(items, schema, calendar)
            prior = json.loads((args.out / "backfill-coverage.json").read_text()) if (args.out / "backfill-coverage.json").exists() else {}
            prior.update(report)
            save(args.out / "backfill-coverage.json", prior)
            print("eligible", sum(r["eligible"] for r in report.values()), "of", len(report), flush=True)
            return
        report = json.loads((args.out / "backfill-coverage.json").read_text())
        with spring("public", args.public_port, args.upstream_port, args.out):
            results = run_promote(args, report, schema, folder, Backend(args.public_port))
        save(args.out / "promote-summary.json", results)


if __name__ == "__main__":
    main()
