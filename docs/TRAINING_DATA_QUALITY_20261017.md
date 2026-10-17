# Source data and training availability — 17 October 2026

Scope: Java ingestion → deployed independent Python → immutable raw → validation →
version → typed final tables. No NEWS ingestion, LLM invocation or Local AI training.
Worktree `fix/training-data-quality` is isolated from the original LLM checkout.

## Changes

- Accept evidence-backed financial metadata from Python (including nullable unknown
  currency/scale and original/restated status). Balance sheets are point-in-time;
  explicit annual statements are annual. Quarterly income/cash basis stays unknown
  without source evidence. Created/modified/fiscal dates never become publication dates.
- Forward ratio date ranges and materialize all validated historical ratios in batches.
  Original source row, normalization, raw hash, version and current-vintage limitation
  remain in each metric input snapshot. Percent ratios retain their established conversion.
- Add native KBS index transport, retaining index points, and BIS monthly FX alongside
  native quarterly/annual observations. History guards allow 15 years for ratio/index/foreign.
- Add `FOREIGN_TRADES` → `MARKET_FOREIGN` → `FOREIGN_TRADE_BUILD`, and company snapshot
  → `SECURITY_REFERENCE` → `SECURITY_REFERENCE_BUILD`. Both require contract PASS audit,
  serialize by security, retain source/raw/version, and reject source/symbol/date mismatch.
  Missing foreign volume remains null; observed industry/shares are not backdated.
- Price source review checks immutable raw against stored OHLC and adjusted close before
  annotating legacy observations. Prefer a verified unadjusted/adjusted pair over an
  unknown basis, then existing official/source priorities. No adjusted-close zero fill.
- Add correction audit for numerical price/macro changes, plus availability views:
  `vw_training_statement_inputs_v1`, `vw_training_market_observations_v1`,
  `vw_training_metric_observations_v1`, `vw_training_macro_observations_v1`.
  Fiscal/as-of/observation dates are distinct from `known_at`. Legacy assumptions are
  explicitly unverified; strict historical eligibility requires actual publication,
  scope, basis, units and original-revision evidence.
- Dedicated-admin endpoints: `GET /api/admin/financial-data/quality/coverage` and
  `POST /api/admin/financial-data/quality/review-legacy/{symbol}`. Token required;
  review is transactional per security and does not manufacture numerical data.
- Price materialization also links any existing canonical foreign observations, so
  the final columns and separate provenance remain correct when foreign data arrives
  before the corresponding price session. This ordering is covered by the isolated
  PostgreSQL ingestion/validation/build test.
- Deploy health includes commit identity; deployment waits for that exact commit.

## Verification and operations performed

- Python: 23 unit/transport tests passed, with mocked upstreams (not live-source proof).
- Java: 176 unit/contract tests passed; PostgreSQL integration tests ran in disposable
  schemas with provider fixtures. Fixtures were not inserted into public.
- Additive migrations were first executed in a disposable schema and rolled back.
  Public migration followed a private compressed backup of the affected existing tables.
- Python production health confirmed version 0.7.0 and commit `13a362217e17b1bb0f3693ac1f5a3bebef5a87b5`.
- Real localhost Java → production Python → public DB smoke materialized historical
  FPT ratios, foreign volumes and company reference. Quarter 3 source returned no
  usable statement and validation rejected it. Do not treat source-empty as a code success.
- Live backfill and post-deployment acceptance are recorded below once complete.

## Material limits

Current upstream financial rows do not prove their historical publication/revision
vintage. `periodEnd + 90 days` is not a verified availability rule. Existing 25 listed
symbols do not establish an unbiased historical universe with delisted constituents.
Current industry/share counts are observed snapshots, not sector/share history.
Adjusted close is provider-defined; corporate action ledgers and total-return methods
remain unverified. Macro annual/rate coverage is limited by the original source.
Some KBS VNINDEX/VN30 OHLC bars fail high/low bounds; those batches remain blocked while
an independent source is checked. Do not split a rejected window to hide invalid bars.

Read-only source investigation also found CafeF VNINDEX OHLC conflicts (including
2026-10-07 high=160.54 against open=1760.54), empty CafeF VN30 history, inconsistent
VCI bars, and HTTP 406 from VNDirect chart. None was patched with invented prices.
