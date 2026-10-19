# Admin control — 19/10/2026

## Verified gaps and changes

The old Java job list returned only active jobs, so disabled jobs disappeared
after reload. The old admin FE sampled 20 runs and 50 validation rows and had no
operational surfaces for LLM/forecast/recovery, coverage or canonical history.
On the real local acceptance database there were 627 jobs (259 active, 368 inactive)
and 10,039 ingestion runs before this acceptance's source request.

The independent FE repository is `Lamprro/HE_THONG_TAI_CHINH_FE_ADMIN`.
This work uses isolated `fix/admin` branches. The original checkout's active,
uncommitted Local AI/H1–H4 work is excluded and must be rebased separately.

- `GET /api/ingestion-jobs?activeOnly=false` lists every job. Default remains
  active-only for legacy clients. `PUT /api/ingestion-jobs/{id}` edits a definition
  while preserving its code/identity and historical runs.
- All `/api/admin/**` and legacy ingestion/raw/source/company/security management
  routes require the dedicated bearer token. Public health remains accessible.
  Existing unauthenticated integrations to these management routes must add the
  token. CORS preflight remains exempt; bearer requests do not require cookies.
- `/api/admin/console/session`, `/runtime`, `/catalog`, `/datasets` and
  `/data/{dataset}` expose authenticated capabilities, actual database checks,
  deployed MVC contracts and bounded allowlisted database browsing. Paging uses
  stable order, exact totals, status/run/search filters and decimal strings. Financial rows include symbol, metric code, period and source labels; symbol and metric searches resolve their reference IDs.
  Raw bodies/transport headers are excluded from list responses; nested
  credentials are redacted. Sensitive account/config tables are not allowlisted.
- `POST /api/admin/console/commands` accepts an idempotency UUID, action, reason
  and input. Native actions: RUN_JOB, MANUAL_INGESTION, VALIDATE_RAW,
  VALIDATE_PENDING, REPLAY_RUN, BUILD_MARKET_PRICE_VERSION. Two database-backed
  workers claim queued rows with SKIP LOCKED. An active-resource index rejects
  concurrent duplicates. Result/error/native run IDs survive browser disconnects.
- Native services still perform fetch, validation, retry policy and canonical
  persistence. One-off inactive job execution never enables its cron schedule.
  Re-fetch reuses saved source parameters and creates a new run, linked in the
  command result. Internal builders require reviewing the current job definition.
- `POST /api/ingestion-jobs/{jobId}/versions/{versionId}/build` selects exactly
  one ACTIVE market-price version and uses the existing writer/lifecycle. Other
  pending versions are untouched; it cannot bypass blocking validation.
- Python `/api/v1/capabilities` is reached through the existing Java adapter at
  `/api/external-financial-data/capabilities`. Registration does not prove source
  health, historical completeness, quota or publication provenance.

## Migration and state semantics

`db/manual/V20261019_01__admin_commands.sql` adds only the command ledger and its
indexes. It was tested in a disposable PostgreSQL schema, then applied after
checking that the public table did not exist and recording that precondition.
No cleanup, financial-value rewrite or LLM-state migration was applied.

QUEUED/RUNNING resources are reserved. Worker heartbeats extend only commands
still in that worker's execution set. A lost result/worker lease becomes UNKNOWN;
it is never replayed automatically. After inspecting original ingestion/domain
runs, an admin can POST `/commands/{id}/acknowledge` with a reason to unlock it.
The token principal is a shared credential, **not a verified human account**.

SUCCEEDED means the native call returned; its result can still report rejection
or NO_CHANGE. ACTIVE is validated, ACTIVATED is built. Existing raw validation
is idempotent and preserves prior audit. The console cannot force a failed rule
to PASS or safely interrupt an in-flight provider/LLM call. Disabling cron affects
future scheduling. Scheduler flags show configuration, not invented heartbeat.

## Evidence

- 182 Java unit/MVC/contract tests plus 5 isolated PostgreSQL ledger tests passed
  (187 total). New cases cover authorization, decimal precision/redaction,
  idempotency, resource conflicts, expired leases, native failure diagnostics,
  disabled one-off dispatch and selected-version isolation.
- Python: 24 unittest tests passed; source transport contracts use mocks.
- FE: TypeScript/build and intercepted form/API tests passed. Two browser suites
  cover legacy flows and confirmed commands/polling, denied login, exact decimal
  text, pagination, typed forecast preview, mobile layout and logout. Browser
  fixture tests never call a provider, LLM or business database.
- Real localhost source ingestion: command `a1b71e21-6b46-431e-84d3-22dd4ac7f14a`,
  run `8bc20ed8-d643-4cf1-a0be-97069f3781e2`, raw
  `7dc463a4-eaeb-4822-9003-d48f602c08a5`, FPT 16/10/2026. Sending the same
  idempotency key returned the same command. Six validation rules passed.
- Version `c996772b-53bf-4e62-9bf3-1f13b7b6104b` became ACTIVATED through native
  selected-version build run `11095ab8-4ed4-4a01-8f38-71af5e3fb3c1`. The existing
  canonical FPT session remains 58,200 VND close/adjusted close with its preserved
  source lineage. Other pending market versions were not swept.

No live LLM execution, forecast generation, news recovery or model training was
triggered by this work. Their UI contracts and audit views are connected, but this
does not certify AI providers or the unfinished Local AI branch as production.
The user explicitly selected **merge FE code only, no new FE hosting project**.
Runtime deployment evidence is recorded after merge; do not infer it from unit tests.
