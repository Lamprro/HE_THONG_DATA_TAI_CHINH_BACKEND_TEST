# Kiểm tra live forecast và WARNING ngày 10/10/2026

## Phạm vi và cách chạy

Người dùng đã chấp thuận rõ gửi snapshot FPT sang Google Gemini và lưu response
đã kiểm định vào DB thật. Chạy bản Java `feature/stock-price-scenarios` tại
`127.0.0.1:8182`; scheduler/seeder tự động tắt, không restart runtime NEWS.
Đây là gọi live, không dùng provider mock. Không sửa code nghiệp vụ để tạo kết quả.

Chạy `scripts/verify_stock_price_live.py --symbol FPT --as-of 2026-10-10` qua
API hiện có: configuration → seed prompt đã có → securities → preview → execute.
Khi execute FAILED, script dừng đúng assertion nghiệm thu; không gọi replay/cache.
Sau đó đọc run qua GET API để lưu audit đầy đủ. Các truy vấn đối chiếu DB dùng
transaction read-only. Không có fetcher/model client thay thế pipeline Java.

## Đầu vào và kết quả thực tế

- Task FINANCIAL_SCENARIOS, prompt version 3 đang bật, input schema v2.
- FPT security ID `24ba9d4d-9a84-4315-b52f-a8905d8005bf`.
- Sáu targets: NET_PROFIT_AFTER_TAX, PRETAX_PROFIT, TOTAL_ASSETS,
  OWNERS_EQUITY, LIABILITIES, STOCK_PRICE; requireMacro=true, horizonQuarters=1.
- Preview eligible=true, 19 kỳ financial, 60 phiên market, 10 quan sát macro.
- Giá nền PRICE:27300, close 59.700 VND/share ngày 08/10/2026, khớp raw nguồn.
- Ngày dự báo 31/03/2027; 5 balance ratios và SMA20/SMA60/return20 tính bằng Java.
- Run `21fc59fc-27e2-42b9-824b-00b5d4529402`, tạo 15:08:37 ngày 10/10/2026
  giờ Việt Nam, provider GEMINI, model gemini-3.8-flash, một attempt.
- HTTP 403, error status PERMISSION_DENIED; provider trả:
  `Your project has been denied access. Please contact support.`
- Run FAILED, errors PROVIDER_HTTP_403 và OUTPUT_MISSING. 403 là lỗi terminal
  trong gateway hiện có; không đổi model/khóa hay lặp request để lách lỗi quyền.
- Một validation round: FORECAST_SCHEMA FAIL; FORECAST_SNAPSHOT và
  FORECAST_PROMPT PASS; SOURCE, EVIDENCE, SCENARIOS, COVERAGE SKIP vì thiếu output.
- Không tạo llm_results mới. Chưa có response giá hợp lệ để kiểm arithmetic,
  evidence, projections hoặc cache của model thật; không tuyên bố nghiệm thu thành công.

| Bảng | Trước | Sau |
| --- | ---: | ---: |
| financial_statements | 3.929 | 3.929 |
| market_prices | 71.762 | 71.762 |
| macro_observations | 137 | 137 |
| llm_runs | 42 | 43 |
| llm_results | 22 | 22 |

Evidence đầy đủ (private/ignored): `target/stock-price-release/FPT-live.json`.
Không commit payload nguồn, cấu hình private hoặc API key. Code snapshot là
1506487; đợt kiểm tra này không thay code/model config hoặc chạy lại bộ unit tests.

## Vì sao kết quả cũ WARNING

Đối chiếu 5 results FINANCIAL_SCENARIOS ngày 04/10/2026: run SUCCESS,
HTTP 200, prompt version 1, quality_status WARNING, output status PARTIAL,
macro_used=false, cả 7 luật kiểm định PASS. Những kết quả đó chỉ có 5 financial
targets, không có STOCK_PRICE; coverage khi chạy là 19 kỳ, 60 phiên, 0 macro.

Nguyên nhân trực tiếp: `ForecastRunStore` đang insert quality_status='WARNING'
cho mọi kết quả forecast đã validate, không suy nhãn VALID tự động từ số luật PASS.
SUCCESS phản ánh hoàn thành flow/publish; PASS phản ánh hợp đồng, nguồn, bằng chứng,
phép tính và audit; WARNING không chứng minh forecast sai hoặc provider lỗi, cũng
không thể hiện xác suất dự báo. Không tự chuyển nhãn để che giới hạn nguồn.

Những giới hạn thực tế lưu trong snapshot/result cũ:

- Thiếu published dates; ingestion time là proxy, chưa đủ backtest point-in-time.
- Report scope UNKNOWN: chưa xác minh riêng lẻ/hợp nhất.
- Lợi nhuận theo kỳ nguồn, chưa xác minh riêng quý/lũy kế; không tự annualize/TTM.
- Giá unadjusted, đơn vị chưa xác minh trong phiên bản cũ, không suy PE/PB.
- Macro không có tại thời điểm chạy; kịch bản chưa hiệu chuẩn/backtest.

Preview mới đã có 10 macro và chứng minh đơn vị giá nền VND/share, nhưng không
tự cập nhật snapshot hoặc response cũ. Hiện còn thiếu scope, basis lợi nhuận,
published dates và macro release/vintage; cổ tức/corporate actions chưa dự báo;
price scenarios chưa backtest/calibrate. WARNING vẫn được code giữ khi publish.

## Việc còn lại

Khôi phục quyền sử dụng Gemini của project/credential hiện cấu hình trước khi
chạy lại full acceptance. Chưa xác định nguyên nhân quản trị khiến Google từ chối
project; không suy diễn thành quota, billing, prompt lỗi hoặc dữ liệu sai từ HTTP 403.
Chỉ sau response hợp lệ mới nghiệm thu sáu targets, evidence, projections, bảy
luật PASS và replay CACHED. Hạ tầng Java production không được deploy trong đợt này.
