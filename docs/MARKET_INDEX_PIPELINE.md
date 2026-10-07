# Market index ingestion pipeline

## Data flow

The existing ingestion framework is used for both collection operations:

```text
ingestion_jobs -> Python API -> ingestion_runs -> raw_payloads
  -> validation_results -> data_versions (MARKET_INDEX / ACTIVE)
  -> INDEX_PRICE_BUILD or INDEX_MEMBERSHIP_BUILD
  -> index_prices or security_index_memberships
  -> data_versions (ACTIVATED)
```

Each successful fetch stores a new raw payload, even when its checksum matches an earlier fetch. A build job only reads a validated `ACTIVE` version whose raw type matches the workflow. Normalized writes, internal run success and version activation share one transaction. A failed build leaves the version `ACTIVE` for retry.

The existing database schema is externally managed (`spring.jpa.hibernate.ddl-auto=none`). Before running build jobs in an environment, review and apply `src/main/resources/db/manual/V20260919_01__market_index_pipeline_constraints.sql`. The script aborts if it finds conflicting existing rows; it never deletes data. It adds source-key and canonical uniqueness for `index_prices`, plus one-open-membership uniqueness and read indexes.

## Seeded catalog

`POST /api/admin/market-indices/catalog/seed` idempotently seeds `VNINDEX`, `VN30`, `HNXINDEX` and eight jobs. It does not run them. The same index catalog is also included in the existing opt-in global catalog seed (`financial.ingestion.catalog.seed-enabled=true`).

| Job | UTC cron | Local time | Parameters |
| --- | --- | --- | --- |
| `VNSTOCK_{INDEX}_INDEX_OHLCV_DAILY` | `0 15 9 * * MON-FRI` | 16:15 Vietnam weekdays | `operation=INDEX_OHLCV`, `provider=vnstock`, `indexCode`, `lookbackDays=7`, `interval=1D` |
| `VNSTOCK_{INDEX}_INDEX_MEMBERS_DAILY` | `0 20 9 * * MON-FRI` | 16:20 Vietnam weekdays | `operation=INDEX_MEMBERS`, `provider=vnstock`, `indexCode` |
| `INDEX_PRICE_BUILD` | `0 */15 * * * *` | every 15 minutes | `workflow=INDEX_PRICE_BUILD` |
| `INDEX_MEMBERSHIP_BUILD` | `0 */15 * * * *` | every 15 minutes | `workflow=INDEX_MEMBERSHIP_BUILD` |

`{INDEX}` is each of `VNINDEX`, `VN30`, `HNXINDEX`. The job engine evaluates cron in UTC. The ingestion scheduler is disabled by default; set `financial.ingestion.scheduler.enabled=true` only when the jobs should run automatically. The validation scheduler is separate and disabled by default. Weekday cron does not exclude market holidays.

## Manual end-to-end test

Use the actual job UUIDs returned by `GET /api/ingestion-jobs`; substitute them for the placeholders below. Base URL is the Java backend. The Python service base URL is configured through `financial.python-service.base-url` in the current deployment.

```bash
curl -X POST "$BASE/api/admin/market-indices/catalog/seed"
curl -X POST "$BASE/api/admin/validation/rules/seed"
curl "$BASE/api/ingestion-jobs"

curl -X POST "$BASE/api/ingestion-jobs/$INDEX_OHLCV_JOB_ID/run"
curl -X POST "$BASE/api/admin/validation/raw-payloads/$RAW_PAYLOAD_ID"
curl -X POST "$BASE/api/ingestion-jobs/$INDEX_PRICE_BUILD_JOB_ID/run"
curl "$BASE/api/admin/market-indices/VN30/prices?page=0&size=50"

curl -X POST "$BASE/api/ingestion-jobs/$INDEX_MEMBERS_JOB_ID/run"
curl -X POST "$BASE/api/admin/validation/raw-payloads/$MEMBERS_RAW_PAYLOAD_ID"
curl -X POST "$BASE/api/ingestion-jobs/$INDEX_MEMBERSHIP_BUILD_JOB_ID/run"
curl "$BASE/api/admin/market-indices/VN30/memberships?page=0&size=50"

curl "$BASE/api/admin/validation/results?limit=50"
curl "$BASE/api/admin/validation/data-versions?limit=50"
```

The collection responses expose `rawPayloadId`. Validate that raw ID before running the matching build job. If validation returns `REJECTED`, inspect the validation results; the build job must not write canonical tables. A build with no accepted versions creates a successful internal run with `noWork=true`.

## Normalization and data policy

Daily OHLCV timestamps accept ISO instants, offset datetimes, Vietnam-local datetimes/dates, and epoch seconds or milliseconds. Only `1d` is stored in V1. The source natural key is `(market_index_id, price_timestamp, interval_code, data_source_id)`. Provider corrections update that row's values and raw/version linkage. One row per index/time/interval is canonical, selected by official source first, then smaller source priority, then smaller source ID.

Membership responses are snapshots, not add/remove events. Their date comes from provider snapshot metadata or, if absent, `raw_payload.fetched_at` in Vietnam time. Weight percentages are divided by 100. A later snapshot closes removed members and opens new members. A changed weight closes the old period and opens a new one; a correction on the original date updates that day's row. Backdated snapshots and a same-day removal that cannot be represented without deleting history are rejected for manual review.

The physical `securities.company_id` field is required. For an unknown member symbol, validation rejects the snapshot instead of inserting an invented company/security. Seed or reconcile the company and security master data, then collect a fresh snapshot. Python `/members` support for each wide index must be confirmed against the running provider; a provider failure is recorded as an ingestion run and does not stop the scheduler's other jobs.
