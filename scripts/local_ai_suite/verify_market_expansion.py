"""Read-only readiness check before expanded-universe ranking training."""
import argparse
from collections import Counter
from datetime import date, timedelta
from pathlib import Path

from market_stage_batch import coverage, market_dates, save
from market_staging import connect

HOSE_SUSPENSIONS = {
    date(2018, 1, 23): "https://baochinhphu.vn/thong-tin-chinh-thuc-ve-su-co-ky-thuat-tai-hose-102233760.htm",
    date(2018, 1, 24): "https://baochinhphu.vn/hose-tiep-tuc-ngung-giao-dich-vao-ngay-24-1-102233813.htm",
}


def indices(start, through, calendar):
    result = {}
    with connect() as db, db.cursor() as cur:
        for code in ("VNINDEX", "VN30"):
            cur.execute("""
                SELECT (p.price_timestamp AT TIME ZONE 'Asia/Ho_Chi_Minh')::date AS day,
                       ds.code, p.open_value, p.high_value, p.low_value, p.close_value,
                       r.id IS NOT NULL AND v.status='ACTIVATED'
                FROM index_prices p JOIN market_indices i ON i.id=p.market_index_id
                JOIN data_sources ds ON ds.id=p.data_source_id
                LEFT JOIN raw_payloads r ON r.id=p.raw_payload_id
                LEFT JOIN data_versions v ON v.id=p.data_version_id
                WHERE p.is_canonical AND p.interval_code='1d' AND i.code=%s
                ORDER BY day
            """, (code,))
            days, invalid, sources = Counter(), [], Counter()
            for day, source, open_, high, low, close, valid_lineage in cur:
                if not start <= day <= through:
                    continue
                days[day] += 1
                if (day in HOSE_SUSPENSIONS or not valid_lineage or any(v is None for v in (open_, high, low, close))
                        or not (0 < low <= min(open_, close) <= max(open_, close) <= high)):
                    invalid.append(day)
                else:
                    sources[source] += 1
            safe = set(days).difference(invalid, [d for d in days if days[d] != 1])
            expected = {date.fromisoformat(d) for d in calendar if start.isoformat() <= d <= through.isoformat()}
            expected.difference_update(HOSE_SUSPENSIONS)
            ratio = len(safe & expected) / len(expected) if expected else 0
            result[code] = dict(first=min(safe, default=None), last=max(safe, default=None),
                                valid_days=len(safe), invalid_days=invalid, sources=dict(sources),
                                duplicate_days=[day for day, count in days.items() if count > 1],
                                expected=len(expected), coverage=ratio,
                                missing=sorted(expected - safe),
                                ready=bool(safe and ratio >= .95 and not invalid and
                                           all(n == 1 for n in days.values()) and min(safe) <= start+timedelta(days=14)
                                           and max(safe) >= through-timedelta(days=35)))
    return result


def verify(out, start, through, target):
    stocks = market_dates("public")
    votes = Counter(d for days in stocks.values() for d in days)
    calendar = {d for d, n in votes.items() if n >= 2}
    details = {t: coverage(d, calendar, date(2021, 1, 1).isoformat(), through.isoformat()) for t, d in stocks.items()}
    checks = indices(start, through, calendar)
    with connect() as db, db.cursor() as cur:
        cur.execute("""
            SELECT count(*) FROM market_prices
            WHERE is_canonical AND interval_code='1d' AND
                (low_price > 0 AND low_price <= high_price
                 AND open_price BETWEEN low_price AND high_price
                 AND close_price BETWEEN low_price AND high_price) IS NOT TRUE
        """)
        bad_stock_ohlc = cur.fetchone()[0]
    count = sum(row["eligible"] for row in details.values())
    report = dict(stock_target=target, eligible_stocks=count, stocks_with_prices=len(stocks),
                  stock_details=details, indexes=checks,
                  stock_ohlc_failures=bad_stock_ohlc,
                  hose_suspensions={day.isoformat(): url for day, url in HOSE_SUSPENSIONS.items()},
                  ready_to_train=count >= target and bad_stock_ohlc == 0 and all(r["ready"] for r in checks.values()))
    save(out, report)
    print("Eligible stocks:", count, "Indexes ready:", {k: v["ready"] for k, v in checks.items()}, flush=True)
    return report


if __name__ == "__main__":
    p = argparse.ArgumentParser()
    p.add_argument("--out", required=True, type=Path)
    p.add_argument("--through", type=date.fromisoformat, default=date(2026, 11, 19))
    p.add_argument("--target", type=int, default=100)
    args = p.parse_args()
    ready = verify(args.out, date(2016, 1, 1), args.through, args.target)["ready_to_train"]
    raise SystemExit(0 if ready else 3)
