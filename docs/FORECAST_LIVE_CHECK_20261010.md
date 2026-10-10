# Kiểm tra live forecast và WARNING ngày 10/10/2026

## Kết quả mới nhất sau cập nhật cấu hình và prompt

Người dùng cập nhật private config và yêu cầu gọi lại. Restart đúng runtime Java
localhost:8182 để nhận cấu hình; không restart NEWS hoặc deploy Java server.
Không commit hay in key. Lỗi 403 của lần đầu dưới đây giữ làm lịch sử.

Lần lại với version 3: run `077e990d-edd1-4002-a3e5-bfdeec31f16e`, Gemini HTTP
200 nhưng REJECTED do MACRO_FLAG_EVIDENCE_MISMATCH: macro_used=true trong khi
mọi evidence_ids chỉ có financial/price. Không tạo result, không sửa response.

Sửa prompt thành **version 4** bất biến mới, thêm yêu cầu và schema description:
macro_used=true khi và chỉ khi ít nhất một scenario cite point domain=MACRO;
macro_available không đồng nghĩa đã sử dụng macro. Không buộc thêm ID không liên
quan để pass. Không đổi validator, response cũ hoặc dữ liệu nguồn.

Chạy lại ForecastPipelineIntegrationTests: **18 pass, 0 failure/error/skipped**,
PostgreSQL schema cô lập, provider **mock**. Hai test mới đối chiếu có macro nhưng
không cite phải reject claim true, và cite hợp lệ/flag true thì publish/cache.
Schema được cleanup; không có response mock trong public. Maven package thành công.

Sau build/restart riêng và seed v4 qua API, chạy nguyên acceptance script hiện có:

- Run `c1d02bfd-5caa-4268-b603-cd11a865bedf`, tạo **15:22:12 10/10/2026** giờ VN.
- Result `e9263b64-2cee-4c21-be16-3cf6a81a8b22` được publish vào DB thật.
- SUCCESS, HTTP 200; Gemini gateway audit 2 attempts: gemini-3.8-flash timeout
  sau khoảng 45 giây, fallback gemini-3.5-flash-lite trả response hợp lệ.
- Đủ 6 targets, 7 luật PASS; source IDs hợp lệ, base mới nhất, tăng trưởng có thứ
  tự, Java tính cả 6 projections đúng Decimal, giá dương và unit VND_PER_SHARE.
- Gọi execute lại cùng request trả CACHED, cùng run/result ID; không gọi Gemini mới.
- Snapshot vẫn 19 kỳ financial, 60 phiên giá, 10 macro; ngày đích 31/03/2027.
- Output PARTIAL, quality WARNING. Có macro trong input nhưng response không cite
  macro, nên macro_used=false là đúng theo luật; không tuyên bố mô hình đã dùng
  macro để xây giả định. Price rationale chỉ cite close nền và một close lịch sử.
- Giá nền PRICE:27300 = 59.700 VND/share ngày 08/10/2026.

| Kịch bản STOCK_PRICE | growth_percent | Giá Java tính, VND/share |
| --- | ---: | ---: |
| bear | -10 | 53.730 |
| base | 2 | 60.894 |
| bull | 12 | 66.864 |

Đây là giả định chưa hiệu chuẩn, không phải bằng chứng độ chính xác dự báo.

Sau hai lần gọi thêm, đối chiếu read-only: financial_statements=3.929,
market_prices=71.762, macro_observations=137 giữ nguyên; llm_runs từ 43 lên 45,
llm_results từ 22 lên 23. Cấu hình DB hiện tại đọc đúng các bảng nguồn này.
Templates 1/2/3 giữ bất biến và disabled, version 4 enabled. Evidence:
`target/stock-price-release/FPT-live.json`, `post-retry-readonly-audit.json`,
`FPT-live-before-config-retry.json`, `FPT-live-v3-macro-rejected.json` (private/ignored).

## Phạm vi và cách chạy

Người dùng đã chấp thuận rõ gửi snapshot FPT sang Google Gemini và lưu response
đã kiểm định vào DB thật. Chạy bản Java `feature/stock-price-scenarios` tại
`127.0.0.1:8182`; scheduler/seeder tự động tắt, không restart runtime NEWS.
Đây là gọi live, không dùng provider mock. Không sửa code nghiệp vụ để tạo kết quả.

Chạy `scripts/verify_stock_price_live.py --symbol FPT --as-of 2026-10-10` qua
API hiện có: configuration → seed prompt đã có → securities → preview → execute.
Ở lần đầu execute FAILED, script dừng đúng assertion nghiệm thu; không gọi replay/cache.
Sau đó đọc run qua GET API để lưu audit đầy đủ. Các truy vấn đối chiếu DB dùng
transaction read-only. Không có fetcher/model client thay thế pipeline Java.

## Lịch sử lần đầu trước cập nhật cấu hình

- Task FINANCIAL_SCENARIOS, prompt version 3 khi chạy lần đầu, input schema v2.
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

Full acceptance của FPT đã đạt với cấu hình mới, nhưng chưa chứng minh model đầu
luôn hoạt động ổn định; một request thành công có fallback không phải kiểm thử tải.
Chưa xác định nguyên nhân quản trị của project/key cũ bị 403. Chưa backtest/calibrate
forecast hoặc bổ sung scope/basis/publication/vintage còn thiếu. Lần này không tạo
kết quả có sử dụng bằng chứng macro dù input có macro. Hạ tầng Java production
chưa deploy; checkout NEWS/master và các runtime khác chưa được cập nhật tự động.
