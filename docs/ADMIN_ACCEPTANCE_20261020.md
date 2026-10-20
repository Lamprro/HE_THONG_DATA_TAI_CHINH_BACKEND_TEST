# Admin live acceptance and progress follow-up — 20 October 2026

Functional release merged: Java [PR 12](https://github.com/Lamprro/HE_THONG_DATA_TAI_CHINH_BACKEND_TEST/pull/12),
FE [PR 2](https://github.com/Lamprro/HE_THONG_TAI_CHINH_FE_ADMIN/pull/2).
Both PR and main CI passed. Java `874bb24d00ea01375a19473d1de5a03e73791ff3`
deployed successfully through workflow
[39454331000](https://github.com/Lamprro/HE_THONG_DATA_TAI_CHINH_BACKEND_TEST/actions/runs/39454331000).
Public health reported this exact SHA; authenticated new API routes returned 200,
missing token returned 403. Python capabilities still reported `7f379e6`, 42 routes.

Real server native FPT OHLCV pipeline for 2026-10-16:

- Command `d00475a2-7cc0-4b53-9620-e8a277aa6d39`, source run
  `7e5268e7-fed8-4d66-9549-5e6699cad8ee`, raw payload
  `9d2609d0-7334-4015-b036-c11149b46c96`.
- Six source rules PASS; version `e5f88ca6-e668-41ff-afa4-8ba677ac04b0`
  ACTIVATED through build run `2ac0e20c-bf16-415c-b6d9-aed56c3e2b66`.
- One canonical FPT session; all 24 unrelated pending versions unchanged.
- API resume and real Chrome FE resume both returned ALREADY_ACTIVATED without
  refetch. Browser command `ce27d8a4-3800-4231-93ae-8e10b4056d6f`.
- Desktop/mobile native browser checks passed for summary, source resume, AI,
  prompt catalog, schedule, history and audit. No JavaScript errors, no browser
  token persistence. No intercepted API in these live checks.

Visual inspection identified one remaining display inconsistency: early terminal
paths (already activated or validation blocked/rejected) returned a final result
but left persisted progress at VALIDATING. The follow-up publishes the terminal
progress before returning, with regressions for accepted/rejected/missing-version
paths. It does not rerun builders or change validation results. Audit tables now
prioritize action/method/path/status/reason/time with readable column labels.

Follow-up validation: 200 Java unit/contract tests passed, including two new
terminal-state regressions; previous 12 isolated PostgreSQL tests remain the
ledger/locking/prompt/schedule evidence (212 total distinct cases). FE TypeScript,
production build and the full-flow intercepted Chrome suite passed again. GitHub
CI runs the complete FE test suites before merge. Final follow-up deployment SHA
and browser verification are recorded with the follow-up PR/release evidence.

The earlier failed mixed-version worker test remains FAILED in the audit. After
stopping localhost and deploying compatible server code, the native pipeline and
browser resume passed. Future unknown command types are skipped by this worker.
Scoped scheduling remains OFF with a real heartbeat. No public prompt changes,
recovery approvals, model calls or Local AI training/code changes occurred.
FE remains merge-only, no new hosting.
