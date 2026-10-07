# Data contract for local AI / LLM (local audit: 2026-09-26)

> Bàn giao cập nhật 07/10/2026: đọc [PROJECT_HANDOVER.md](PROJECT_HANDOVER.md) trước. Code LLM đã push ở `feature/llm-processing` (`5343f81`), chưa merge/deploy. Các số liệu DB, kết quả API và nhận định bên dưới thuộc thời điểm kiểm tra được ghi trong tài liệu; không phải xác nhận runtime ngày 07/10. Implementation và việc còn dở cần đối chiếu với bàn giao mới.

The first trusted analysis scope is FPT, ACB, BID, HPG and VCB. Keep source
provenance and validation state in the AI context; do not send raw provider
responses or assume that every table is complete.

## Read gates

- Daily equity prices: `market_prices.interval_code = '1d'` and
  `is_canonical = true`. Interpret `price_timestamp` as the Vietnam trading
  date at 00:00 Asia/Ho_Chi_Minh. API: `GET
  /api/admin/market-prices/{symbol}?interval=1d&page=0&size=100` (paginated).
  Never merge this with `snapshot` quotes.
- Financial statements: `financial_statements.is_current = true` and
  `is_canonical = true`; join `financial_statement_items` by statement ID.
  Preserve statement type, report scope, fiscal period, currency and
  `unit_scale`; do not sum items from different statement types.
- Index prices: `index_prices.interval_code = '1d'` and
  `is_canonical = true`. Expose missing dates explicitly rather than filling
  prices by interpolation.
- Retain `data_source_id`, `raw_payload_id` and `data_version_id` for every
  observation so an AI answer can be traced to the validated source.

## Verified local snapshot

| Domain | Current coverage | AI use |
| --- | --- | --- |
| Equity daily OHLCV | 1,246 canonical trading days per symbol, 2021-09-27 through 2026-09-25 | Usable for five-year charts and price analysis |
| Quarterly statements | 60 canonical statements / 20 periods per symbol | Usable with items, period and unit metadata |
| Index daily OHLCV | HNXINDEX 1,246; VN30 1,246; VNINDEX 1,244 | Usable with VNINDEX gap flag |
| VNINDEX 2021-12-10 and 2023-05-15 | Source `close` is below `low`; raw retained and validation rejected | Exclude; do not invent replacement prices |
| Financial metrics | FPT 12; other four symbols 0 | Not yet a complete metric source |
| Index memberships | 0 | Do not use |
| Macro observations | 0 | Do not use |
| News | Uneven coverage, not five years for all five symbols | Use only with explicit date/provenance caveats |

The Python `market_price.v1` response is in PR #6, not the currently deployed
Python API. Until it is merged and deployed, Java still receives legacy provider
envelopes and applies compatibility parsing. `INDEX_MEMBERS` still returns 502
on the deployed Python API; the Python branch fix requires a deployed live test.
`NEWS_DATA_FETCH` received 404 in the last end-to-end run because the deployed
Python API lacked `/api/v1/url-fetch`. Python `main` now contains the raw URL
route (commit `22af13d`), while branch `fix/news-article-normalization` adds
publisher-specific extraction and Vietnam-time publication dates. The branch
was tested against a real CafeF FPT article (HTTP 200), but deployment and the
end-to-end Java NEWS flow remain unverified. Until then, exclude NEWS content
from AI inputs.
Five NEWS versions (one per target symbol) were rejected by the old Java
workflow when this missing route returned 404. The local Java code now leaves
transport failures retryable, but those five historical rejected versions need
a controlled requeue or a fresh accepted NEWS collection after Python deploy.
The local `NEWS_ARTICLE_BUILD` now matches clean title/sapo/body against active
companies, aliases and securities, stores score and match evidence per relation,
and uses an idempotent relation insert. The development DB has the additive
`news_article_companies.match_evidence` column. As checked on 2026-09-26, it has
100 old `NEWS_DATA` raw payloads with no `extraction_status`, one already
`ACTIVATED` NEWS_DATA version, zero ACTIVE NEWS_DATA versions and zero built
news articles/relations. These old rows will not be rebuilt automatically or
accepted by the new extraction rule; a fresh API-backed fetch or controlled
replay after Python deployment is still required.

The local Spring Boot scheduler is opt-in. The `local,five-symbols` profiles
enable it with an explicit 38-job allowlist and validation symbol allowlist;
the default profile does not start all 257 active jobs. For index OHLCV, keep
scheduled lookback at 120 calendar days: the VnStock API was observed to cap
a five-year request at 100 rows. Historical backfill must use bounded windows
and validate each window before build.
