# Kiểm nghiệm forecast mở rộng trên localhost — 10/10/2026

## Phạm vi

Người dùng chốt chỉ kiểm thử localhost. Không gọi/deploy backend trên server,
không merge master, không restart checkout/runtime NEWS. Java test bind
127.0.0.1:8182, scheduler và seeder tự động tắt, ddl-auto=none.
Không thay pipeline bằng fetcher, model client hoặc writer DB tự dựng.

## Thay đổi phục vụ kiểm nghiệm

API GET `/api/admin/forecasts/runs/{id}` bổ sung **validationRoundId** lấy từ
llm_runs.validation_round_id. `validations` vẫn chứa mọi vòng audit. Không sửa
prompt v4, schema output v2, luật validation hoặc cách tính giá. Không migration.

Script nghiệm thu hiện có `scripts/verify_stock_price_live.py` được mở rộng:

- Lưu audit run kể cả execute thất bại, trước assertion nghiệm thu.
- `--revalidate`: gọi đúng API revalidate, kiểm không tăng provider attempts.
- Kiểm đúng 7 rule codes PASS thuộc validationRoundId hiện hành, không ép toàn
  bộ lịch sử chỉ có 7 rows sau khi revalidate tạo thêm round.
- `--extended-checks`: preview horizons 1–8, cutoff lịch sử, invalid requests,
  ineligible execute và 8 concurrent cache requests (4 workers).
- Script chỉ dùng API Java, không tự tính giả định forecast hoặc ghi response DB.
  Công thức Python/Decimal chỉ đối chiếu arithmetic của Java với output đã lưu.

## Tests đã chạy

ForecastPipelineIntegrationTests: **32 pass**, 0 failures/errors/skipped, lúc
15:42:47 giờ Việt Nam. PostgreSQL schema forecast_test_<uuid> cô lập, source copy
từ DB thật, Gemini **mock**; mutation nguồn rollback và schema cleanup. Mẫu model
chỉ nằm trong schema test. Đã sửa một lỗi accessor trong test lúc compile trước
khi chạy thành công; không tính lần compile thất bại là test pass.

Thêm 14 ca kiểm nghiệm so với 18 ca trước:

| Nhóm | Điều được chứng minh qua flow Java |
| --- | --- |
| Cutoff | Quote nhận sau cutoff không làm nền; báo cáo công bố sau cutoff bị loại |
| Readiness | 59 phiên, giá cũ hơn 15 ngày hoặc daily date trùng đều dừng trước provider |
| Provenance | Data version REJECTED không thể chứng minh giá; nguồn đổi lúc model trả thì không publish snapshot cũ |
| Horizon | Preview 1–8 quý đúng; horizon 1/2 gọi riêng; revalidate không kích hoạt lại result đã superseded |
| Đồng thời | Hai request cùng input, một provider đang chạy: request sau nhận RUNNING cùng run, chỉ một model call |
| Audit | Revalidate qua HTTP giữ 14 audit rows và chỉ rõ 7 PASS của vòng hiện hành |
| Response lỗi | Sai ngày đích, base không mới nhất, kịch bản sai thứ tự hoặc thiếu target đều REJECTED |

Regression: **150 tests xét, 146 pass, 4 opt-in skipped**, 0 failures/errors,
kết thúc 15:43:54. Không chạy những integration cũ có thể ghi mẫu vào DB chung.
Tổng đợt này **178 pass, 4 skipped**. Maven package thành công 15:44:53.
Không suy từ mock/regression rằng đã đo accuracy hoặc độ ổn định của Gemini.

## Nghiệm thu API localhost với response thật đã lưu

```powershell
python -X utf8 scripts/verify_stock_price_live.py --symbol FPT --as-of 2026-10-10 --revalidate --extended-checks
```

Chạy bắt đầu **15:45:20**, dùng response Gemini thật đã lưu từ lần live trước:

- Run c1d02bfd-5caa-4268-b603-cd11a865bedf,
  result e9263b64-2cee-4c21-be16-3cf6a81a8b22, prompt v4.
- Execute CACHED, revalidate SUCCESS, đọc result và kiểm arithmetic/evidence của
  đủ 6 targets, replay CACHED cùng IDs. Đợt này **không gọi Gemini mới**.
- Vòng hiện hành 0f0fa55c-b40b-488c-bc37-8c5a5d2f3884: đủ 7 rule codes PASS.
  Lịch sử giữ 14 rows (2 vòng), provider attempts vẫn 2 từ lần live trước.
- Cả 8 request cache đồng thời trả CACHED cùng IDs; 0 provider attempts phát sinh.
- Preview horizons 1–8 lần lượt 31/03, 30/06, 30/09, 31/12 năm 2027 và 2028.
  Đây là preview ngày đích, không gọi 8 forecast model hoặc thay current horizon.
- Thiếu admin credential bị HTTP 403; horizon 0/9 hoặc cutoff tương lai bị 400.
- Execute với cutoff 31/12/2025 thiếu dữ liệu trả SKIPPED, không tạo run/result.

Giá FPT giữ nguyên: nền 59.700 VND/share ngày 08/10/2026, ngày đích 31/03/2027;
bear −10% → 53.730, base +2% → 60.894, bull +12% → 66.864. Input có 10 macro,
response không cite macro nên macro_used=false. WARNING/PARTIAL còn đúng; không
đổi forecast hay gán VALID chỉ vì thêm test pass.

Đối chiếu DB read-only trước/sau:

| Bảng | Trước | Sau |
| --- | ---: | ---: |
| financial_statements | 3.929 | 3.929 |
| market_prices | 71.762 | 71.762 |
| macro_observations | 137 | 137 |
| llm_runs | 45 | 45 |
| llm_results | 23 | 23 |

Chỉ audit thêm vòng kiểm định qua API; không sinh số liệu nguồn/kết quả giả.
Evidence private/ignored trong target/stock-price-release: FPT-live.json,
FPT-before-extended-checks.json, post-retry-readonly-audit.json,
extended-isolated-tests.log, extended-regression-tests.log, extended-package.log.
Không commit token/config private hoặc các payload/evidence này.

## Điều chưa thể kết luận

Preview lịch sử dùng nguyên cutoff/availability hiện có và requireMacro=true:

| Ngày chốt | Kỳ financial | Phiên market | Macro | Eligible |
| --- | ---: | ---: | ---: | --- |
| 31/12/2025 | 0 | 0 | 0 | false |
| 31/03/2026 | 0 | 0 | 0 | false |
| 30/06/2026 | 0 | 0 | 0 | false |
| 30/09/2026 | 20 | 60 | 0 | false — macro required but missing |

Các điểm được trả đều trước hoặc bằng ngày chốt và availableAt trước ngày hôm
sau theo Asia/Ho_Chi_Minh. Không backdate created_at/publication để tạo cảm giác
backtest được; không bỏ cutoff hoặc thay macro thu thập sau đó vào snapshot cũ.
20 kỳ ở 30/09 so với 19 kỳ ở 10/10 do phạm vi rolling 5 năm khác ngày bắt đầu.

Đây là chứng minh flow hoạt động/chặn dữ liệu đúng, **chưa đo độ chính xác giá**.
Ngày đích FPT 31/03/2027 chưa tới; các mốc cũ ở trên chưa có đủ snapshot cho
full-flow backtest. Thiếu publication/basis/scope/vintage chuẩn và lịch sử giá
điều chỉnh vẫn được giữ trong limitations. Không báo MAE/MAPE/hit-rate tự dựng.
Không gọi mã khác hoặc benchmark tải lớn; không chứng nhận Java production.
