# Admin workflows — 20 October 2026

Continues the October 19 release on `fix/admin-flows`, isolated from the original
checkout's unfinished Local AI work. Python remains the existing independently
deployed adapter (`7f379e6`); no custom provider fetcher or financial writer added.

## Operator functionality

- Structured source → raw validation → selected-version canonical build, with
  persisted stage/source run/version/build result. Supported operations: OHLCV,
  QUOTE, FINANCIAL_STATEMENT, RATIO, INDEX_OHLCV, INDEX_MEMBERS, FOREIGN_TRADES,
  COMPANY. Uses the existing native parsers, writers, lifecycle and validation.
- Resume an existing source run without fetching again; an activated version
  returns ALREADY_ACTIVATED. Native selected-version builds now cover statements,
  metrics, index prices/membership, foreign trades and security references as well
  as prices. Unrelated pending versions are not swept by this mode.
- Durable typed commands for NEWS, forecast, recovery and historical metrics.
  Domain outcomes remain distinct from command SUCCEEDED. Explicit cancellation
  only before execution; reviewed retries link to their parent. Partially saved
  source pipelines retry as PROCESS_RUN. UNKNOWN requires review first.
- Paginated commands/runs/quality/admin audit, exact provenance filters, canonical
  selectors and immutable issue notes. Notes never change a validation FAIL.
- Native job advisory locks cover manual, queue and scheduler entry points across
  instances. Canonical request hashing makes object key order irrelevant.
- Scoped cron control: default OFF, explicit selected active jobs, optimistic
  version, reason, real heartbeat and sweep timestamps. Rejects enabling alongside
  the legacy ingestion/macro scheduler. Disabling does not interrupt running work.
- Dedicated forecast/news/recovery UI including native preview, results, attempts,
  validation rounds and separate recovery proposal/review/analyze actions. Forecast
  execution requires an eligible preview of the unchanged input in the UI; the
  native backend remains responsible for eligibility and validation at execution.
- Prompt detail/checksum/schemas, immutable new versions initially disabled,
  explicit promotion, previous enabled version disabled under task lock. Existing
  run FKs, input/output schemas and engine validation contracts are preserved.
- Actual database aggregate counts and authenticated mutation HTTP audit, without
  storing tokens, request bodies or query strings. Shared admin token does not
  establish the identity of an individual human.

## Migration and deployment

Apply `V20261020_01__admin_flow_controls.sql` after October 19 migration, before
starting the new runtime. Adds ledger progress/parent and CANCELLED, event audit,
and disabled scoped scheduler policy. Applied to the shared DB on October 20 after
backing up its seven existing commands; status counts preserved, no financial data
rewritten. Migration is manual, not automatically applied by CI/deploy.

Do not run old and new command workers against the same queue during acceptance
or rolling upgrade: an old worker does not know the new actions. A real browser
resume during local/server mixed-version testing exposed this: the old server
recorded FAILED / `Unknown persisted action` before domain execution. The local
worker was stopped before deployment. That failure audit is retained; it must not
be overwritten. Drain active commands before replacing a runtime.

## Verification already run before commit

- Java: 210 tests passed, zero failures/errors/skipped, 198 unit/contract plus 12 real
  PostgreSQL ledger/prompt/schedule/locking tests in disposable schemas. Native
  provider/model services in these tests are mocked. Package passed.
- FE: TypeScript and production build; 11 unit tests and three intercepted Chrome
  suites passed. New suite covers pipeline, cancel/retry, issue audit, forecast
  eligibility, recovery approval separated from analysis, prompt versions/promotion,
  disabled schedule update, mobile sizing and no browser token persistence.
  Fixtures do not write to public DB or call a provider/model.
- Real localhost Java→deployed Python→VNDirect FPT OHLCV for 2026-10-16:
  command `0bc5e296-4963-4bdf-9e72-83920ffbf7b7`, source run
  `5c887dce-d891-4beb-bbd1-18162618b036`, version
  `543b06c1-13c9-44ad-bf6f-6fddea400164` ACTIVATED after six PASS rules.
  Resume returned ALREADY_ACTIVATED, one canonical FPT session, 24 other pending
  versions unchanged. Missing admin token returned 403; summary/schedule/prompts/
  labeled candidates/source capabilities returned 200, schedule remained disabled.
- Real queued forecast PREVIEW succeeded, with eligible input and explicit
  publication/scope/profit-basis/unadjusted-price/macro-vintage limitations. Preview
  is not an accuracy certification. No forecast execution, NEWS model execution,
  recovery proposal, prompt mutation or scheduler enablement was tested on public.

## Boundaries and remaining evidence

Management/subsidiaries/events remain raw-only native operations; no canonical
table flow is invented for them. NEWS/MACRO use their own existing native jobs.
The native API catalog retains less common endpoints alongside dedicated screens.
Provider registration does not guarantee upstream availability or full ten-year
coverage. This release does not certify historical point-in-time training data.
FE is merge-only: user declined creating new hosting. Post-deploy SHA, GitHub PRs
and real browser/server acceptance will be recorded in the release follow-up.
The new worker also skips unsupported future action types when claiming commands; a disposable-schema regression test passed. This cannot retrofit an already-running October 19 worker, so the documented cutover requirement still applies.
