# Health DB và phiên bản dự báo theo quý — 11/10/2026

Nhánh: `feature/forecast-periods-and-health`. Phạm vi được yêu cầu triển khai: P02 và L05.
Giữ nguyên writer/revision financial (P03); chưa thêm bảo mật, claim ingestion job,
index context, luật cân đối hay scheduler forecast. Không đổi quy tắc tăng trưởng âm.

## P02 đã triển khai

- `GET /api/health` dùng connection pool của ứng dụng, chạy `SELECT 1`.
- DB UP trả 200; lỗi/timeout trả 503 với mã lý do an toàn, không lộ thông tin kết nối.
- Deadline mặc định 2 giây, cấu hình `financial.health.database-timeout` (tối đa 10 giây).
- Các request đồng thời dùng chung probe đang chạy; không xếp hàng thêm connection.
  Nếu pool/driver tiếp tục đợi sau deadline, caller vẫn nhận 503 trong deadline.
- `GET /api/health/live` chỉ kiểm tra ứng dụng còn nhận request.
- Deploy workflow hiện đã gọi `/api/health`, nên khi deploy code này sẽ kiểm tra DB luôn.
  Health không chứng nhận schema, provider, coverage hoặc độ chính xác dự báo.

## L05 đã triển khai

`llm_results` có thêm `forecast_period_start`, `forecast_period_end`, `target_scope`.
`as_of_date` là ngày chốt dữ liệu; `is_current` quản lý bản hiện hành. Task_code
vẫn là FINANCIAL_SCENARIOS, các metric_code vẫn ở forecasts JSON.

Khóa unique bản current: security + task + kỳ dự báo + danh sách target đã sắp xếp.
Các kỳ khác nhau và các tập target khác nhau được giữ độc lập. Cùng kỳ/phạm vi,
response mới đạt validation sẽ thay bản current trong một transaction, không xóa lịch sử.
Lỗi provider/validation không hạ bản trước. Request có ngày chốt cũ hơn không thay bản
ngày chốt mới hơn. Cache có thể trả kết quả lịch sử cho request giống hệt mà không
tái kích hoạt kết quả đó. Revalidation kết quả đã bị thay thế chỉ phục vụ audit.

Request cũ không có forecastPeriodEnd vẫn dùng horizonQuarters 1–8 như trước.
Request có forecastPeriodEnd chọn quý hiện tại/tương lai cố định (không chọn quý đã
kết thúc trước asOfDate, không xa hơn tám quý). forecastPeriodEnd phải là cuối quý.
Ví dụ dự báo Q4/2026 và chạy lại trong tháng 11 vẫn giữ nguyên kỳ này:

```json
{
  "securityId": "<UUID của mã>",
  "asOfDate": "2026-10-11",
  "horizonQuarters": 1,
  "forecastPeriodEnd": "2026-12-31",
  "targets": ["NET_PROFIT_AFTER_TAX", "PRETAX_PROFIT", "TOTAL_ASSETS",
    "OWNERS_EQUITY", "LIABILITIES", "STOCK_PRICE"],
  "requireMacro": false
}
```

Preview trả ngày đầu/cuối kỳ; results trả ngày đầu/cuối kỳ và targetScope.
GET `/api/admin/forecasts/securities/{id}/results?forecastPeriodEnd=2026-12-31`
lọc bản hiện hành theo kỳ. Lịch sử request/response vẫn xem qua runs/run API.
Khi input đổi mà bản mới chưa qua kiểm định, bản cũ vẫn được giữ trong DB; API
không đảm bảo tiếp tục hiển thị nó nếu snapshot nguồn của bản đó không còn hợp lệ.

Prompt phiên bản 5 làm rõ kỳ cố định. Response schema vẫn v2 và Java tính projections.
Explicit-period preview/execute bị chặn có lý do nếu active prompt chưa hỗ trợ kỳ này.
Sau deploy code mới, dùng API seed template hiện có để kích hoạt v5. Không tự kích
hoạt prompt mới trên DB khi runtime server vẫn còn code cũ.
API: `POST /api/admin/forecasts/template/seed` với quyền admin. Trước khi đổi prompt,
đợi forecast đang RUNNING/PENDING_VALIDATION hoàn tất; xác nhận configuration báo
version 5 và preview explicit period eligible. Không cần reseed các prompt NEWS.

Migration `V20261011_01__forecast_result_periods.sql` backfill kỳ/phạm vi từ JSON
có thật; giữ lịch sử, chọn ngày chốt mới nhất khi có nhiều bản current cùng khóa.
Trigger tương thích writer cũ không truyền cột mới, lấy kỳ/phạm vi từ response JSON.
Nó không tạo response LLM, không sửa financial/market/news và không gọi provider.
Migration cần chạy trong transaction có backup; script kiểm thử idempotency và
writer cũ trong schema riêng trước khi áp dụng vào schema nghiệp vụ.

## Các mục cần làm rõ, chưa sửa

### P03: giữ nguyên revision nguồn

Financial giữ cơ chế tạo revision mới và inactive revision trước của cùng báo cáo.
DB đối chiếu có 29 revision financial inactive và 3.900 current/canonical. Hai
trường hợp current tham chiếu raw có thời điểm lấy cũ hơn revision trước đã được
đối chiếu: 53 item chung mỗi trường hợp, không có giá trị khác nhau. Chưa có bằng
chứng số tài chính bị ghi đè sai. Market hiện cho phép cập nhật giá cùng business
key từ raw mới và bỏ qua raw cũ; không áp cơ chế financial revision cho mọi bar.
Nhánh này không thay đổi cả hai writer đó.

### P04: retry chỉ áp dụng ở đâu

Raw validation có lỗi blocking: không tạo version ACTIVE để build. Materialization
financial/market/macro lỗi: rollback transaction ghi bảng cuối, ingestion_run FAILED,
version ACTIVE được chuyển REJECTED; vòng build tiếp theo không chọn lại REJECTED.
Không có retry nội bộ cho version đó. Một raw/version mới là một lần xử lý mới.

NEWS_DATA_FETCH là bước gọi ngoài: lỗi fetch có thể giữ NEWS version ACTIVE để
lịch sau thử lại. RETRY_PENDING/COMPLETED_WITH_REJECTIONS là status của response
workflow, không phải enum ingestion_runs. Budget của job hiện là 10 lỗi (Redis);
API manual không qua job không dùng budget này. Adapter Java gọi Python một lần.
Retry Python/provider phải xét từng endpoint, không áp mặc định 3–4 cho mọi API.
Lỗi validation response LLM có thể dẫn đến một lần gọi model mới theo giới hạn
ba run cho input không đổi; đây là retry provider, không phải retry ghi dữ liệu nguồn.
Vấn đề reset budget khi workflow tổng hợp báo lỗi một phần vẫn cần chốt chính sách,
chưa thay đổi trong nhánh này.

### P05: cùng API và scheduler gọi

Hai lượt có thể cùng fetch, tạo run/raw riêng. Unique business key, khóa writer,
canonical và kiểm tra replay quyết định bảng cuối; không khẳng định mọi lượt tạo
hai bản nghiệp vụ. Market cùng source/bar có thể update/skip; financial có thể
tạo revision và hạ bản cũ theo yêu cầu hiện tại; NEWS giữ một bài và bổ sung quan hệ.
Một lượt cạnh tranh có thể bị constraint/version-state từ chối. Rủi ro chính là
call/cost/audit lặp và lỗi một lượt; chưa thêm claim job.

### P06: kế hoạch chuẩn metadata financial

DB đã kiểm tra: 3.900 financial_statements current/canonical đều report_scope
UNKNOWN và published_at NULL. Không phải kết luận thiếu metadata NEWS/market.

1. Đối chiếu contract Python và raw provider thật: report_scope, ngày công bố,
   currency/unit_scale, riêng quý/lũy kế. Phân biệt ngày công bố và ngày lấy dữ liệu.
2. Đề xuất bổ sung period_basis (STANDALONE_QUARTER/YTD/UNKNOWN) và provenance
   metadata; cập nhật parser StatementDraft, factory/writer và validation. Không
   mặc định đơn vị/kiểu báo cáo khi nguồn chưa chứng minh. Ratio chỉ ghép kỳ/scope
   phù hợp; YoY/QoQ/TTM chỉ tính khi basis đã xác minh.
3. Backfill metadata chỉ từ raw/công bố có bằng chứng qua pipeline; mục không đủ
   chứng cứ tiếp tục UNKNOWN. Kiểm thử đơn vị nghìn/triệu, scope khác nhau, báo cáo
   lũy kế và ngày công bố sau cutoff trong schema cô lập trước khi cập nhật DB.

### P08: link hiển thị và nội dung hợp lệ là hai điều kiện riêng

Lưu URL không gây vấn đề. Khoảng trống là thiếu raw body/HTML, upstream HTTP lỗi
có thể bị luật ERROR chặn trước builder URL_ONLY; builder chỉ hỗ trợ FAILED,
chưa hỗ trợ SKIPPED/thiếu status. Recovery nhận article_id, nên URL chưa có article
thì không thể đi vào recovery. Đề xuất giữ URL/quan hệ job từ danh sách nguồn
trước điều kiện full text; missing content vẫn URL_ONLY kèm lý do. Chỉ nội dung
đạt validation, và recovery được admin duyệt, mới dùng cho task phân tích NEWS.
Không tạo content giả hoặc coi lỗi fetch là dữ liệu nội dung hợp lệ.

### L01, L02, L03, L04 và L06

- L01: có thể thêm luật sau projections kiểm tra tài sản ≈ liabilities + equity
  cho cùng scope/kỳ, có dung sai; không tự sửa số hoặc coi liabilities tăng là
  kết quả tốt. Điểm sửa là ForecastValidationService: lấy projections Java cho
  từng bear/base/bull, đánh giá qua catalog luật response LLM, lưu vào validation
  round trước khi ForecastRunStore publish. Chỉ áp khi đủ ba target tương thích;
  thiếu target/scope chưa rõ thì ghi hạn chế, không tự bịa số để cân đối. Chưa triển
  khai vì người dùng đang hỏi làm rõ.
- L02: growth âm đang hỗ trợ, không đề xuất cấm. Giá trị nền <=0 hiện bị prompt/
  validator từ chối; cải thiện nhỏ có thể là preview báo trước thay vì tốn call
  rồi reject. Chưa đổi validator, schema, hoặc thêm flow nền âm.
- L03: macro parser đảm bảo quan sát khớp source, mã/unit/frequency/country đúng;
  không chứng minh có mọi chuỗi/kỳ mới. Đề xuất ForecastContextService thêm
  macro_coverage theo từng series (latest_date, observation_count, missing/stale)
  trong snapshot input. Tại build(), tổng hợp coverage sau SELECT macro observations,
  đưa series thiếu/cũ vào limitations và source hash; sửa resource prompt để đọc
  coverage và phản ánh vào rationale. ForecastValidationService tiếp tục chỉ cho
  cite ID có thật. Freshness cần cấu hình theo frequency/lịch công bố, không dùng
  một ngưỡng ngày cho mọi series. Không thêm luật reject raw chính thức chỉ vì thiếu
  kỳ nguồn chưa công bố. Lãi suất 2023 có trong DB nhưng không nằm cửa sổ 12 tháng.
- L04: giữ context hiện tại theo quyết định người dùng, chưa thêm index context.
- L06: chưa triển khai backtest/calibration; giữ source hash, prompt, attempts và
  validation rounds cho nghiệm thu sau.

## Bằng chứng nghiệm thu

- 11/10/2026, 23:30 giờ Việt Nam: `mvn verify` thành công, 216 tests, không failed,
  errors hoặc skipped: 176 unit/contract và 40 PostgreSQL forecast integration.
  Các integration DB NEWS/macro/enrichment ngoài phạm vi không chạy trong đợt này.
  Integration dùng provider mock và schema cô lập, không gọi Gemini/Python thật.
  Có HTTP health/readiness, API lọc kết quả theo quý, chặn prompt chưa hỗ trợ kỳ,
  thay bản/cached history, quý và scope độc lập, writer cũ, rollback và migration.
- 11/10/2026, 23:23:58 giờ Việt Nam: migration đã commit vào schema `public` của
  DB cấu hình hiện tại. Giữ nguyên 23 results, 45 runs; backfill 6 financial results;
  không có duplicate current cần inactive. Đối chiếu toàn bộ response và run audit
  không đổi; chạy migration lần hai ngay trong transaction xác nhận idempotency.
- Backup riêng tư và manifest tại `target/forecast-period-release/20261011T162358Z/`,
  bị ignore bởi Git. Không ghi response giả vào schema nghiệp vụ. NEWS recovery
  PENDING_VALIDATION được giữ nguyên; migration không thay đổi các prompt.
- Chưa deploy/restart server, chưa kích hoạt prompt v5 ở DB thật và chưa gọi Gemini
  để tạo dự báo mới. Kiểm thử health có cả HTTP thật của ứng dụng test và các lỗi
  connection/timeout; không dùng health để chứng nhận production toàn hệ thống.
