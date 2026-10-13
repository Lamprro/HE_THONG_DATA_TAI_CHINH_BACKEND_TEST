# Phân loại chất lượng forecast theo validation — 13/10/2026

Fix thực hiện riêng trên main từ 8babf09. Không kéo P02/L05/P06/P08/L03 chưa
merge từ feature/forecast-periods-and-health. Không deploy server, đổi prompt
đang active, sửa số liệu nguồn hoặc cập nhật hàng loạt kết quả trong DB public.

## Luồng và ý nghĩa nhãn

`claim → gọi LLM → stage response trong llm_runs → validate response → ghi
validation_results → tính projections Java → lưu llm_results`.

- FAIL của luật ERROR/CRITICAL: không publish kết quả mới, giữ audit; REJECTED
  với lỗi nghiệp vụ hoặc FAILED với lỗi kỹ thuật/provider/config.
- Chỉ có FAIL của luật WARNING: run SUCCESS, kết quả quality_status=WARNING.
- Toàn bộ luật PASS: run SUCCESS, kết quả quality_status=VALID, kể cả response
  PARTIAL đã phản ánh đúng limitations theo contract.

VALID xác nhận response vượt qua luật hiện hành; không xác nhận dự báo chính
xác/backtested. Response status, dữ liệu về limitations và các giả định giữ
nguyên. Không lấy PARTIAL hay danh sách limitations làm một nhãn WARNING ngầm.

ForecastValidationService.Evaluation tách blocking errors và warnings; audit
mỗi luật giữ severity, PASS/FAIL/SKIP, rule snapshot và validation_round_id.
ForecastRunStore lấy chất lượng từ evaluation cho cả INSERT và revalidation
UPDATE. Không còn literal WARNING cố định ở writer llm_results.

## Luật chặn lỗi và luật cảnh báo

Giữ 7 luật bắt buộc có severity ERROR/CRITICAL: FORECAST_SCHEMA, FORECAST_SOURCE,
FORECAST_EVIDENCE, FORECAST_SCENARIOS, FORECAST_COVERAGE, FORECAST_SNAPSHOT,
FORECAST_PROMPT. Không thể hạ một luật bắt buộc thành WARNING để bỏ qua schema,
bằng chứng hay nguồn sai. Thiếu luật bắt buộc trả VALIDATION_CONFIGURATION_ERROR
trước khi gọi provider. Luật bổ sung hỗ trợ severity WARNING/ERROR/CRITICAL và
các executor forecast đã biết; executor lạ vẫn là lỗi cấu hình.

Thêm executor tùy chọn FORECAST_DATA_LIMITATIONS để catalog chọn rõ hạn chế
nguồn cần cảnh báo. Nó chỉ có hiệu lực khi admin cấu hình luật active trong
validation_rules, data_domain=LLM_FORECAST. Ví dụ rule_config:

```json
{
  "version": "1",
  "tasks": ["FINANCIAL_SCENARIOS"],
  "limitationPrefixes": ["MACRO_NOT_AVAILABLE:"]
}
```

Với severity WARNING, snapshot không có macro tạo FAIL/WARNING trong audit và
kết quả vẫn publish WARNING. Với severity ERROR, cùng vấn đề chặn publish.
Không có limitation khớp prefix thì luật PASS và không làm hạ nhãn VALID.
Prefixes phải là array không rỗng gồm chuỗi không rỗng. Không tự seed luật mới
hay tự đổi catalog DB public trong đợt fix này; catalog 7 luật mặc định đạt hết
thì kết quả VALID theo đúng yêu cầu.

## Đọc kết quả, cache và revalidation

API đọc kết quả bỏ điều kiện count(PASS)=7. Thay bằng kiểm tra toàn bộ luật
cấu hình ở đúng vòng audit: đủ rule snapshot hiện hành, luật chặn lỗi PASS,
WARNING có thể FAIL. Thiếu/trùng audit, snapshot khác hoặc blocking FAIL không
được hiển thị. Không trộn các vòng audit trước.

Cache cũng kiểm tra vòng audit này: cache WARNING hợp lệ vẫn CACHED; audit
thiếu/không đạt trả REVALIDATION_REQUIRED, không gọi provider lại hoặc trả bản
cache như kết quả hợp lệ. Revalidation cập nhật chất lượng trên cùng bản ghi,
giữ các vòng cũ và không nhân bản kết quả.

Fingerprint có phiên bản engine forecast-validation-quality-v2 để không dùng
cache/phân loại từ logic cũ. Sau khi deploy, bản WARNING lịch sử cần đi qua
`POST /api/admin/forecasts/runs/{runId}/revalidate` bằng flow hiện có để đổi nhãn
theo validation mới; không đổi WARNING lịch sử bằng SQL hàng loạt. Các bản
chưa revalidate không được trả như đã qua policy mới. Cần hoàn tất/pause các
lượt đang pending trước khi chuyển runtime/policy; pending dưới policy cũ sẽ
không tự được coi là đạt policy mới.

## Kiểm thử và giới hạn nghiệm thu

Kiểm thử tích hợp dùng service thật và PostgreSQL schema riêng, LLM provider
mock; không gửi fixture vào DB nghiệp vụ. Bao phủ all-pass VALID, WARNING FAIL
vẫn lưu/đọc/cache, WARNING PASS vẫn VALID, blocking FAIL chặn dù có WARNING,
revalidation WARNING→VALID→WARNING giữ một bản ghi và audit, không hạ severity
luật bắt buộc, audit thiếu/FAIL không đọc hoặc dùng cache. Schema clone giữ
trigger tương thích period đang có ở DB public vì LIKE không sao chép trigger;
không bỏ constraint để test pass. Regression hoàn tất **22:12 ngày 13/10/2026,
giờ Việt Nam**: **235 tests pass**, 0 failures/errors/skipped (172 unit/contract,
38 forecast integration, 20 NEWS LLM và 5 recovery integration). Maven
verify/package thành công; log private dưới target/quality-regression.log.

Fix này chỉ thay phân loại llm_results của FINANCIAL_SCENARIOS. Không đổi nhãn
financial_metrics, công thức, schema response hoặc các task NEWS.
