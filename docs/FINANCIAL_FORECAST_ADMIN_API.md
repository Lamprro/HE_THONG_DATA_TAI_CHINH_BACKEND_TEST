# Financial/market/macro → LLM: luồng dự báo và API admin

Cập nhật 04/10/2026. Chỉ triển khai Java/database local, chưa push hoặc deploy. API khách hàng và giao diện dashboard không nằm trong thay đổi này.

## 1. Phạm vi đã triển khai

Luồng `FINANCIAL_SCENARIOS` đọc dữ liệu tài chính có sẵn, thị trường và macro nếu có,
đưa sang Gemini đề xuất kịch bản, kiểm tra bằng luật DB, rồi Java tính giá trị dự báo.

Năm mục tiêu được hỗ trợ rõ ràng:

- NET_PROFIT_AFTER_TAX: lợi nhuận sau thuế từ INCOME_STATEMENT.
- PRETAX_PROFIT: lợi nhuận trước thuế từ INCOME_STATEMENT, không lấy mục trùng trong CASH_FLOW.
- TOTAL_ASSETS: tổng tài sản từ BALANCE_SHEET.
- OWNERS_EQUITY: vốn chủ sở hữu từ BALANCE_SHEET.
- LIABILITIES: nợ phải trả từ BALANCE_SHEET; không tự đổi thành nợ vay.

Mỗi chỉ tiêu có ba kịch bản bear/base/bull. Đây là giả định chưa hiệu chuẩn, không phải xác suất, khoảng tin cậy thống kê hoặc cam kết đầu tư.
Không tự triển khai mọi chỉ tiêu bất kỳ chỉ bằng cách thêm tên vào request. Mục tiêu mới cần mapping nguồn, đơn vị, công thức, schema và test riêng.
Chưa tạo giá mục tiêu, tín hiệu mua/bán, ROE/ROA TTM, EPS hoặc PE/PB nếu thiếu dữ liệu/cơ sở đơn vị đáng tin cậy.

## 2. Tình trạng nguồn dữ liệu thật

Database không có bảng `macro_data`: hiện dùng `macro_series` và `macro_observations`.
Cả hai đang rỗng ở lúc triển khai. Không điền macro giả, không gửi lãi suất/CPI/GDP do AI tự nghĩ ra.

Financial context lấy tối đa năm năm gần asOfDate từ các báo cáo canonical/current,
đồng thời kiểm tra kỳ và thời điểm công bố/thu thập không vượt cutoff.
FPT, ACB, HPG, BID và VCB hiện có 19 kỳ đủ điều kiện và 60 giá đóng cửa ngày gần nhất trong preview.
VNM có market nhưng chưa có financial history đủ điều kiện; execute trả SKIPPED.

`financial_metrics` trước thay đổi có 12 bản ghi không canonical. Context không dùng chúng như số liệu đã được chứng nhận.
Chỉ số đã có chỉ được đưa vào context nếu canonical, quality_status VALID, đúng công ty/mã và không vượt asOfDate.
Ngoài ra Java tính các tỷ lệ từ báo cáo nguồn, kèm công thức và ID các input.

## 3. Kiến trúc và phân tách trách nhiệm

```text
Admin Bearer credential → FinancialForecastAdminController → DTO đã kiểm tra
    → ForecastContextService: financial + market + macro + snapshot/coverage
    → FinancialRatioCalculator: tính tỷ lệ bằng BigDecimal
    → ForecastPromptService: prompt/schema có version trong DB
    → ForecastRunStore.claim: cache, retry cap, khóa nhận việc
    → GeminiLlmGateway: HTTP thật + fallback, llm_run_attempts
    → ForecastRunStore.stage: lưu response PENDING_VALIDATION
    → ForecastValidationService: luật DB + validation_results
    → Java tính projected value từ base và giả định đã validate
    → llm_results FINANCIAL, liên kết company/security/run/template
```

| Thư mục/lớp | Trách nhiệm |
|---|---|
| dto/forecast/ForecastRequest | Input admin: mã, ngày cutoff, horizon, targets, yêu cầu macro |
| dto/forecast/ForecastDtos | Preview, coverage, source points, ratios, stored metrics, run, result, outcome, configuration, template state |
| controller/admin/FinancialForecastAdminController | API admin, @Valid DTO, không tự gọi model hay tính toán |
| config/ForecastAdminAccessConfiguration | Credential admin fail-closed, constant-time comparison, cho phép preflight CORS |
| service/forecast/ForecastContextService | Đọc/snapshot nguồn, chống dữ liệu tương lai/thiếu/nhập nhằng, coverage, persisted ratios |
| service/financial/FinancialRatioCalculator | Công thức số học thuần, không dùng LLM, không chạy biểu thức tùy ý từ DB |
| service/forecast/ForecastPromptService | Seed immutable prompt, đọc active, liệt kê version và bật/tắt |
| service/forecast/FinancialForecastService | Điều phối preview/execute/recovery; không giữ transaction khi gọi mạng |
| service/forecast/ForecastValidationService | Đọc bảy luật LLM_FORECAST, schema/source/evidence/scenario/snapshot, audit và projections |
| service/forecast/ForecastRunStore | Lưu run/attempt/response/result, revalidate, cache, lease, khóa chống chạy trùng |
| service/llm/GeminiLlmGateway | Tái sử dụng gateway và giới hạn fallback đã có của NEWS |

NEWS pending query đã được giới hạn `news_article_id IS NOT NULL` để không xử lý nhầm run forecast.
Forecast không chạy thêm vòng ingestion_job/raw_payload/data_version cho response AI.

## 4. Tính chỉ số tài chính

Các công thức từ BALANCE_SHEET cùng kỳ/scope/đơn vị:

| Code | Công thức | Đơn vị |
|---|---|---|
| LIABILITIES_TO_EQUITY | LIABILITIES / OWNERS_EQUITY | x |
| LIABILITIES_TO_ASSETS | LIABILITIES / TOTAL_ASSETS * 100 | % |
| EQUITY_TO_ASSETS | OWNERS_EQUITY / TOTAL_ASSETS * 100 | % |
| CURRENT_RATIO | CURRENT_ASSETS / SHORT_TERM_LIABILITIES | x |
| CASH_RATIO | CASH_AND_CASH_EQUIVALENTS / SHORT_TERM_LIABILITIES | x |

Không chia cho 0/âm, không ghép kỳ hoặc đơn vị khác nhau, không tùy chọn một bản ghi khi trùng input.
Thiếu input thì không tạo con số. Calculator không đổi LIABILITIES thành debt-to-equity nợ vay.
Các tỷ lệ cho ngân hàng cần được diễn giải theo ngành, không áp máy móc tiêu chuẩn doanh nghiệp phi tài chính.

API recalculate ghi `financial_metrics`: is_derived=true, is_canonical=false, quality_status WARNING,
calculation_version balance-ratios-v1, calculation_key chống insert trùng, input_snapshot có input values/IDs/đơn vị/kỳ/công thức.
Không tự nâng các phép tính có report_scope UNKNOWN thành canonical.

Lần chạy thật FPT đã tạo 95 tỷ lệ từ 19 kỳ; gọi lại cùng dữ liệu insert 0.
Đây là phép tính từ số thật, không phải 95 dự báo AI và không ghi đè các chỉ số gốc.

## 5. Request/response LLM

Prompt `financial_scenarios.json` v1 được seed vào llm_prompt_templates, domain FINANCIAL.
Request/response schema là của template DB; thay nội dung template phải tăng version.
API bật/tắt không sửa JSON lịch sử hoặc ghi đè nội dung phiên bản đã có.

Input `financial.forecast.input.v1` có:

- company_id, security_id, symbol, company_name, industry.
- as_of_date, forecast_period_end, forecast_basis.
- targets[]: các chỉ tiêu yêu cầu.
- points[]: ID FSI:/PRICE:/METRIC:/MACRO:, domain, code, value, unit, periodStart/periodEnd, periodType, scope, availableAt.
- derived_metrics[]: công thức và sourcePointIds.
- macro_available và limitations[].

Không gửi mọi cột/raw HTML vô hạn. Market là 60 close gần nhất, macro tối đa 120 observations trong 12 tháng,
metrics canonical tối đa 100, financial giới hạn kỳ năm năm và các input đã hỗ trợ.
Các bảng macro vẫn là nguồn thực, không tự lấy kiến thức bên ngoài của Gemini làm dữ liệu DB.

Output provider `financial.forecast.output.v1` bắt buộc:

- schema_version, task_code FINANCIAL_SCENARIOS.
- company_id, security_id, as_of_date, forecast_period_end phải khớp input.
- status SUFFICIENT/PARTIAL/INSUFFICIENT và overview.
- forecasts[]: metric_code, base_point_id và bear/base/bull.
- Mỗi scenario: growth_percent, rationale, evidence_ids[].
- macro_used, risks[], limitations[].

Base là điểm tài chính cuối của đúng chỉ tiêu. Mỗi scenario phải dẫn base và ít nhất một điểm lịch sử khác của cùng chỉ tiêu.
growth_percent phải trong [-100,300] và bear <= base <= bull. Base <=0 bị từ chối: không dùng công thức tăng trưởng tương đối này cho nền 0/âm.
Một target chỉ xuất hiện một lần, và tập targets phải đúng request.

Java bổ sung `calculated_values[]` vào JSON lưu/trả admin sau validation:

`projected_value = base_value * (1 + growth_percent / 100)`

Mỗi dòng có metric_code, base_value, unit, base_point_id, source_period_end, forecast_period_end,
bear/base/bull numeric, formula, calculation_version và forecast_basis.
LLM không được tự tính/ghi giá trị tiền dự báo rồi bỏ qua công thức Java.
Schema response provider kiểm tra output AI gốc; calculated_values là phần Java bổ sung trong hợp đồng stored result.

## 6. Bảy luật validation dùng chung bảng

data_domain của catalog là LLM_FORECAST; validation_target của audit là LLM_OUTPUT.

| Luật | Kiểm tra |
|---|---|
| FORECAST_SCHEMA | Readiness/HTTP/output và JSON Schema đầy đủ |
| FORECAST_SOURCE | Công ty, mã, ngày cutoff và ngày mục tiêu |
| FORECAST_EVIDENCE | Điểm nguồn tồn tại, đúng latest target và có lịch sử tham chiếu |
| FORECAST_SCENARIOS | Đủ target, không trùng, nền dương, thứ tự scenario; schema giới hạn growth |
| FORECAST_COVERAGE | Đủ lịch sử, không INSUFFICIENT, nhận diện limitations, macro_used phù hợp evidence |
| FORECAST_SNAPSHOT | Nguồn hiện tại còn phù hợp snapshot gửi |
| FORECAST_PROMPT | Template còn active/checksum khớp, policy luật không đổi trong lần gọi |

Thiếu/tắt/hạ severity luật nền, executor/config/task sai đều chặn trước provider hoặc FAILED khi phát hiện trong validation.
Mỗi lần kiểm tra ghi round/snapshot riêng vào validation_results, nối llm_run_id.
Schema lỗi làm luật phụ SKIP nhưng không publish; lỗi ERROR/CRITICAL làm REJECTED.
HTTP/transport hoặc lỗi kỹ thuật làm FAILED. Output/raw log được giữ, không chỉnh số để hợp thức hóa response.

Macro rỗng: requireMacro=true → SKIPPED; false → được phân tích PARTIAL không dùng macro.
Macro được khai báo sử dụng phải có point macro thực trong evidence, không chỉ đặt macro_used=true.
Vì có limitations nguồn, SUFFICIENT bị từ chối; kết quả hiện tại luôn WARNING và ghi rõ chưa hiệu chuẩn.

## 7. Lưu và truy nguồn

Tái sử dụng llm_runs, llm_run_attempts, validation_rules, validation_results, llm_results.
Migration V20261004_05__financial_forecasts.sql bổ sung:

- company_id/security_id FK ở runs/results; as_of_date ở results.
- Constraint tách NEWS với FINANCIAL source; NEWS giữ news_article_id, FINANCIAL giữ company/security/asOfDate.
- Unique active run theo security/task; unique current result theo security/task/asOfDate.
- Index lịch sử run theo security; provenance và calculation_key cho financial_metrics.
- Hai definition tỷ lệ mới và bảy luật forecast, ON CONFLICT DO NOTHING.

Không ghi forecast vào financial_statements/financial_statement_items/financial_metrics như actuals.
Không ép ghi vào bảng predictions bằng cách bịa model_version/dataset/features: bảng đó hiện phục vụ hợp đồng mô hình phân loại khác.
Kết quả dự báo JSON trong llm_results có FK công ty/mã/run/template, source_hash, input_hash, round/policy.
Đây là same shared LLM flow nhưng khác input/validation business với NEWS.

## 8. Retry, cache và phục hồi

Gateway dùng lại timeout 45 giây/attempt, tổng 180 giây/task, tối đa 8 attempts và một attempt/model mặc định.
Lỗi 404/408/429/500/502/503/504 hoặc IOException có thể chuyển model; credential/request lỗi không chạy hết model.
Forecast không tự bypass lỗi output bằng đổi model; execute mới vẫn phải qua cùng validation.

Claim khóa theo security; cùng input current trả CACHED; active/pending trả RUNNING.
Ba FAILED/REJECTED cùng input dừng RETRY_LIMIT. RUNNING quá 10 phút hết lease; worker cũ không stage/publish được.
Input hash gồm template, routing, luật và request canonical JSON; không lệ thuộc thứ tự khóa JSONB.

Response lưu PENDING_VALIDATION trước, rồi validate/publish trong transaction ngắn.
Recovery không gọi Gemini lại. Lượt recovery xử lý từng run, lỗi một run trả RECOVERY_ERROR để không chặn các run sau.
Revalidate dùng response đã lưu và luật hiện tại; không sinh response giả.
Current result đạt cập nhật round/policy/input hash, để execute sau đổi luật có thể cache.
Result không đạt bị retire. Không hồi sinh kết quả lịch sử để thay bản current khác.
Result API lọc nguồn hash, active template, policy và đủ bảy PASS trước khi trả admin.

Chỉ một cấu hình forecast current cho mỗi security/task/asOfDate: horizon/targets mới thay bản current cũ cùng ngày,
nhưng giữ bản cũ lịch sử. Nếu cần so sánh nhiều cấu hình current song song, cần version contract/index theo forecast specification.
Chưa bật generation scheduler hàng loạt cho forecast; API cho admin chạy/kiểm tra có kiểm soát.

## 9. Admin credential và CORS

API mới dưới `/api/admin/forecasts/**` kiểm tra `Authorization: Bearer <admin credential>`.
Credential riêng dài ít nhất 32 ký tự, không dùng Gemini key.
Đã sinh credential bằng nguồn ngẫu nhiên mật mã và đặt trong application-local.properties được Git bỏ qua; không ghi giá trị vào báo cáo/log.
Có thể cấu hình `financial.admin.api-token` hoặc FINANCIAL_ADMIN_API_TOKEN theo mẫu môi trường.
Không cấu hình hợp lệ: HTTP 503; thiếu/sai credential: 403. Lỗi dùng ApiErrorResponse chung.
Browser CORS preflight OPTIONS được phép theo CorsConfiguration; request thật vẫn phải xác thực.

Hiện project chưa có luồng đăng nhập/session/RBAC đang dùng để nối tài khoản admin. Đây là credential dành riêng quyền admin,
không phải tuyên bố đã xây dựng login/JWT/role khách hàng. Khi có authentication account thực, thay interceptor bằng principal/role gate.
Không nhúng credential trong frontend bundle/Git, không cấp cho khách hàng, dùng HTTPS khi ra server và giới hạn origins cụ thể.
Các API admin cũ ngoài namespace mới không được tự động bảo vệ bởi interceptor này; cần đánh giá security chung trước mở Internet.

## 10. API cho dashboard

Base `/api/admin/forecasts`. Tất cả request thật cần credential admin.

| Method | Endpoint | Request → response |
|---|---|---|
| GET | /configuration | Configuration: provider/admin configured, supportedTargets, task, template version, scheduler false |
| GET | /securities?limit=100 | SecurityOption[]: securityId/companyId/symbol/companyName |
| GET | /templates | TemplateSummary[] cả enabled/disabled để có thể bật lại |
| GET | /template | Template active và schema/prompt |
| POST | /template/seed | SeedResponse: inserted; seed immutable resource version |
| PATCH | /template/{id}/enabled | TemplateStateRequest → TemplateState |
| POST | /preview | ForecastRequest → Preview: eligible, hash, coverage, issues, input, schema |
| POST | /execute | ForecastRequest → Outcome: status/runId/resultId/issues |
| POST | /metrics/recalculate | ForecastRequest → MetricCalculation: metrics/provenance/limitations/inserted |
| GET | /securities/{id}/metrics?limit=100 | StoredMetric[]: bao gồm canonical/quality/provenance để admin phân biệt dữ liệu |
| GET | /securities/{id}/results?limit=10 | Result[]: JSON hợp lệ/current còn khớp nguồn/luật |
| GET | /securities/{id}/runs?limit=20 | UUID[] của run, tải chi tiết qua endpoint bên dưới |
| GET | /runs/{runId} | Run: source, provider/model, HTTP, tokens/latency, request/response, attempts/validations/errors |
| POST | /runs/{runId}/revalidate | Outcome; không gọi generation |
| POST | /validation/pending?limit=5 | Outcome[] của các response chờ kiểm tra |

### Request ví dụ FPT

```json
{
  "securityId": "24ba9d4d-9a84-4315-b52f-a8905d8005bf",
  "asOfDate": "2026-10-04",
  "horizonQuarters": 1,
  "targets": ["NET_PROFIT_AFTER_TAX", "PRETAX_PROFIT", "TOTAL_ASSETS", "OWNERS_EQUITY", "LIABILITIES"],
  "requireMacro": false
}
```

horizonQuarters 1–8, targets 1–5 enum hợp lệ, security/date bắt buộc. Ngày tương lai bị từ chối.
Ngày mục tiêu là cuối quý tương lai tính từ asOfDate, không lén cộng từ kỳ báo cáo cũ.
Đầu vào này cho forecast_period_end 2026-12-31. Phiên bản v1 chỉ so với giá trị reported cuối, không cam kết quarter standalone/TTM đã được chuẩn hóa.
PATCH template dùng `{"enabled":false}` hoặc true; không gửi prompt mới để sửa đè version đang dùng.

Status chính: SUCCESS/CACHED/RUNNING/SKIPPED/CONFIGURATION_REQUIRED/VALIDATION_CONFIGURATION_ERROR/RETRY_LIMIT/
FAILED/REJECTED/LEASE_LOST/RECOVERY_ERROR. HTTP 200 của execute không tự nghĩa dự báo đã publish: phải đọc Outcome.
Thiếu template/task disabled được báo lỗi cấu hình/argument qua error contract, không gọi provider.

## 11. Giới hạn cần nghiệm thu trước chạy hàng loạt

- Nhiều báo cáo thiếu published_at: dùng created_at conservatively, không lấy kỳ kết thúc làm thời điểm biết thông tin.
- Report scope UNKNOWN và cơ sở riêng quý/lũy kế chưa xác minh: hạn chế có trong input/result, không tự suy ra TTM/annualized.
- Market close chưa xác nhận đơn vị và corporate-action adjustment: dùng làm bối cảnh, không tự PE/PB hoặc giá mục tiêu.
- Macro không có release/vintage timestamp chuẩn: availableAt là proxy thu thập, không phải bộ backtest point-in-time.
- Current/canonical financial là lựa chọn bảo thủ; không tái dựng đầy đủ lịch sử các revision mà nhà đầu tư biết ở quá khứ.
- Evidence ID có thật không chứng minh lập luận kinh tế đúng. Growth là giả định LLM có giới hạn, chưa được training/calibration/backtest hay kiểm tra accuracy.
- Các chỉ tiêu v1 là kịch bản từng chỉ tiêu, chưa phải bộ báo cáo tài chính dự phóng cân đối đồng thời. Không dùng chúng như bảng cân đối kế toán tương lai hoặc tự tính tỷ lệ từ các giá trị dự báo độc lập.
- Không thể hứa mọi chỉ số tương lai đã được triển khai hoặc dự báo chính xác; năm mục tiêu và năm tỷ lệ là phạm vi executable v1.
- Nguồn mới hoặc policy/template đổi có thể ẩn result cũ; admin phải revalidate hoặc execute theo nhu cầu.
- Admin key không thay thế security review/account RBAC trước production.

Thiết kế công khai giả định/uncertainty tham khảo cách [IMF mô tả các kịch bản và độ bất định dự báo](https://www.elibrary.imf.org/display/book/9798229042758/CH001.xml).
Đây là tham khảo phương pháp trình bày, không dùng forecast IMF làm dữ liệu macro hoặc bằng chứng cho company forecast.

## 12. Nghiệm thu local

- Bộ hồi quy NEWS, gateway, validation và forecast: 123 tests, 0 failures, 0 errors, 0 skipped trong lần chạy cuối 04/10/2026. Test mô phỏng chỉ ghi schema riêng rồi dọn; DB không còn schema test.
- API preview thật: FPT/ACB/HPG/BID/VCB đủ 19 kỳ; VNM thiếu financial history bị chặn.
- FPT thật: run e2ebb603-31a3-44d0-a8a6-3f93c5c3d40b, result 63462f1d-dbdb-4718-97ce-6e66ddc7f705, model gemini-3.8-flash.
- ACB thật: run 6dd1dd8f-73d5-4c3a-a2f3-c2745cfe8e35, result 832d2d67-91e8-4acf-b3db-4df2f3d1c295, model cuối gemini-3.5-flash-lite; hai attempts có fallback.
- HPG thật: run 753090e9-6f18-4026-99f1-bfd01eab6985, result 763a2481-cb01-4f6f-9203-2da8af933add; model gemini-3.5-flash-lite, hai attempts.
- BID thật: run ce74822e-c58c-4e58-83c0-36108efbced7, result c637fc58-684c-4e30-bc04-1ee08b39b4cc; model gemini-3.5-flash-lite, hai attempts.
- VCB thật: run 406310c0-0622-4d77-96fc-e6a646133e4b, result af2c0367-559e-4816-b76a-a86afc17889b; model gemini-3.8-flash, một attempt.
- Mỗi response đủ năm chỉ tiêu, bảy luật audit và SUCCESS; gọi lại CACHED.
- Đối chiếu DB cuối: 40 runs, 57 attempts, 22 results lịch sử; 6 NEWS và 5 FINANCIAL đang current. Năm kết quả forecast đều liên kết company/security/as_of_date, mỗi vòng validation hiện hành đủ bảy PASS, quality WARNING; không còn forecast RUNNING/PENDING_VALIDATION.
- Revalidate FPT/ACB thành công mà không gọi Gemini thêm; giữ result ID, thêm vòng audit. Thiếu macro khi requireMacro=true trả SKIPPED/MACRO_REQUIRED_BUT_MISSING.
- Không có macro numeric được chèn. 95 derived ratios FPT được tính từ input thật; recalculation lặp insert 0.
- Request không credential admin trả 403; CORS preflight từ localhost:5173 trả 200 nhưng POST thật vẫn cần credential. Ngày/horizon không hợp lệ bị từ chối. GET templates và stored metrics hoạt động trên bản localhost cuối.
- Chưa deploy/push; generation scheduler forecast chưa bật, NEWS scheduler vẫn tắt ở phiên local.

Kết quả SUCCESS nghĩa hợp đồng, provenance và công thức kiểm tra đạt; không phải đánh giá accuracy của một mô hình dự báo đã được hiệu chuẩn.
