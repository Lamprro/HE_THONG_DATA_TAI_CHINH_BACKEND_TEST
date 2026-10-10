# Hợp nhất backend vào main — 10/10/2026

## Trạng thái và phạm vi

Nhánh mặc định GitHub đã được đổi từ master sang main qua repository API.
Nhánh main khởi tạo từ master sau PR #8 chặn deploy tự động; PR tổng hợp đưa
lịch sử các luồng đã kiểm thử từ integration/main-consolidation vào main.
Master được giữ làm lịch sử, không phải nhánh phát triển/release mới.
Các nhánh cũ và checkout của AI khác được giữ; không reset hoặc xóa công việc.

PR theo luồng, đều merge vào nhánh tích hợp bằng merge commit giữ ancestry:

| PR | Nội dung |
| --- | --- |
| [#3](https://github.com/Lamprro/HE_THONG_DATA_TAI_CHINH_BACKEND_TEST/pull/3) | LLM + MACRO, dispatcher và scheduler riêng |
| [#4](https://github.com/Lamprro/HE_THONG_DATA_TAI_CHINH_BACKEND_TEST/pull/4) | Enrichment, RATIO, backfill và tỷ lệ actual |
| [#5](https://github.com/Lamprro/HE_THONG_DATA_TAI_CHINH_BACKEND_TEST/pull/5) | STOCK_PRICE, horizon quý tương lai và validation round |
| [#6](https://github.com/Lamprro/HE_THONG_DATA_TAI_CHINH_BACKEND_TEST/pull/6) | Hợp nhất metrics cũ với writer/calculator có provenance |
| [#7](https://github.com/Lamprro/HE_THONG_DATA_TAI_CHINH_BACKEND_TEST/pull/7) | Nối lịch sử ingestion đã có patch tương đương |
| [#8](https://github.com/Lamprro/HE_THONG_DATA_TAI_CHINH_BACKEND_TEST/pull/8) | Deploy thủ công, merge vào master trước khi tạo main |

Tất cả 12 đầu nhánh gốc trên GitHub đã được xác nhận là ancestor của source tổng
hợp sau fetch trước PR cuối. Không đưa lại patch ingestion tương đương, không
cho writer metrics legacy cạnh tranh với writer enrichment. Job CALCULATE dùng
HistoricalFinancialMetricService của API admin; source version, input IDs, đơn
vị, canonical và idempotency được giữ. Mỗi security là một transaction; failure
sau một security giữ audit FAILED và replay idempotent cho phần đã commit.

## Kiểm thử thực sự chạy

- 172 unit/contract pass, 0 failed/errors/skipped, kết thúc 16:18:53 giờ Việt Nam.
- 72 PostgreSQL integration pass, 0 failed/errors/skipped, kết thúc 16:21:46.
- Breakdown DB: forecast 32, NEWS LLM 20, attempts 4, recovery 5, MACRO 5,
  enrichment 5, market backfill 1. Provider trong các suite này là mock;
  synthetic response/fixtures chỉ nằm trong schema test riêng.
- Thêm 8 kiểm thử dispatcher/allowlist và 1 DB replay test cho job CALCULATE.
  NEWS/recovery test tách sequence defaults kế thừa public trước khi ghi fixture.
- Maven package thành công lúc 16:27:04. Có một lỗi biên dịch khi thêm persister
  audit metrics đã được sửa trước các lượt pass; không tính lần lỗi là pass.
- Python acceptance script chạy preview-only không seed template nữa; đã gọi
  trực tiếp API Java thành công. Không dựng provider/fetcher/writer DB thay pipeline.

CI mới chạy unit/contract và Maven verify trên PR/main bằng Java 21, không có
credential DB nghiệp vụ. Các suite DB opt-in vẫn chạy trong môi trường có quyền
CREATE SCHEMA, không dùng workflow CI để ghi mẫu vào database dùng chung.

## Smoke localhost và đối chiếu chỉ đọc

Bản đóng gói tổng hợp chạy 127.0.0.1:8183, ddl-auto=none, mọi scheduler/seeder tắt.
Nghiệm thu qua API thật, không gọi model hoặc ghi response mới:

- GET /api/health trả UP.
- POST /api/admin/forecasts/preview FPT, asOfDate 2026-10-10, horizon 1,
  6 targets, requireMacro=true: eligible=true, 19 financial periods, 60 market
  sessions, 10 macro observations, forecastPeriodEnd=2027-03-31.
- GET /api/admin/macro/coverage: 11 series, 137 observations Việt Nam.
- GET /api/external-financial-data/health: Java adapter gọi Python production,
  HTTP 200, version 0.6.0, macro_country VNM.
- GET run Gemini thật đã lưu c1d02bfd-5caa-4268-b603-cd11a865bedf trả SUCCESS,
  validationRoundId 0f0fa55c-b40b-488c-bc37-8c5a5d2f3884, 2 attempts.

DB chỉ đọc lúc 16:29:17: financial_statements 3929, market_prices 71762,
macro_observations 137, news_articles 359, llm_runs 45, llm_results 23;
khớp snapshot trước đợt tích hợp. Không chạy migration hoặc backfill public.
Một schema forecast_test_bcc4dfa6147344ac98adf661a0a924d1 còn được quan sát trong
DB; không có bằng chứng xác định chủ sở hữu nên giữ lại, không cleanup tùy tiện.

Bằng chứng riêng tư nằm trong target/integration-*.log, integration-readonly-audit.json
và target/stock-price-release/FPT-preview.json; không commit credential hoặc raw samples.

## Deploy và giới hạn còn giữ

Deploy Spring Boot chỉ workflow_dispatch từ main. Merge/create main không deploy
server; không restart runtime NEWS hoặc các checkout khác. Migration DB vẫn thủ
công với preconditions/backup/schema đích, không tự chạy vì đã merge code.

Horizon quý đã được sửa và nghiệm thu. Các hạn chế nguồn về scope/publication,
riêng quý/lũy kế, macro vintage, unadjusted prices vẫn giữ. Không khẳng định accuracy,
backtest, total return hay kịch bản tài chính đã cân đối. Recovery URL/PDF/Cloudinary
provider thật, admin RBAC toàn hệ thống, Redis retry budget và vận hành nhiều worker
vẫn cần nghiệm thu đúng môi trường; các mock tests không chứng nhận production.
