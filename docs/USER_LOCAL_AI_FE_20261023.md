# Nối gói Local AI mới và client FE — 23/10/2026

Tiếp tục nhánh `feature/user-api` từ `64b2b17`; chỉ commit/push, chưa PR/merge/deploy.
Thư mục `HE_THONG_DATA_TAI_CHINH_AI_LOCAL` và checkout gốc **chỉ được đọc**.
Không sửa Python, PKL, input, manifest, fact NPAT, schema hoặc quá trình train.

## Tiến độ đã đối chiếu

Chat **DỰ ÁN LOCAL AI - TÁCH TỪ LUỒNG LLM** đã bàn giao gói
`local_ai_release_20261023`, package `1d5b62a8-6eaf-417a-adbe-fa899f76a429`.
Đọc README, release manifest, source requirement, native SuiteService/Contract,
migration ở checkout gốc và đối chiếu catalog/count DB bằng kết nối read-only.
Gói có 11 task, 34 PKL, 218 input và 218 kết quả selected đã lưu. Trạng thái vẫn
RESEARCH_ONLY, production_eligible=false, replace_llm=false.

Chat **Thiết kế UI UX admin và user** đang vẽ Figma, chưa có bản user UI tích hợp
để nghiệm thu. `_admin_fe` có commit `f0790ba` bổ sung cột audit, còn là FE admin.
`FINANCIAL_FRONTEND_TEST_V1/src/api.ts` vẫn dùng company ID số, DTO cũ, POST analyze/
predict và admin fixture/train. Không nối thẳng các request này vào API công khai
hoặc giả chúng đã tương thích. Đã thêm client độc lập trong repo Java để FE dùng
đúng hợp đồng, không sửa giao diện mà chat khác đang làm.

## API mới và thay đổi tương thích

Hiện **35 GET** dưới `/api/v1`, client có đủ 35 operation và OpenAPI theo build.

| GET | Tham số | Kết quả |
| --- | --- | --- |
| `/api/v1/local-ai/models` | `packageId` UUID tùy chọn, `page=0`, `size=25` (1–100) | Danh mục task của gói research v1 được đăng ký gần nhất, hoặc gói chọn rõ; metadata hợp đồng + coverage |
| `/api/v1/securities/{symbol}/local-ai` | `exchange`, `packageId` | Trạng thái/snapshot/result count của đúng ticker, không kèm giá trị dự đoán |

`analysis/availability.localAi.registeredSuite` nhận diện bốn bảng registry mới,
khác với thống kê `model_versions` cũ. `securities/{symbol}/overview.localAi`
ghép progress của chính mã vào màn chi tiết. Các field cũ được giữ nguyên.

Catalog có `modelId`, `horizonQuarters`, `targetPeriod`, `selectedAlgorithm`,
`developmentGatePassed`, `registeredInputs`, `firstInputAsOf`, `lastInputAsOf`,
`storedSelectedResearchResults`, `outputContract`, readiness và cờ công bố.

- Catalog đọc đúng schema `local_ai.registered.release.v1` và gói/model có cờ false
  JSON boolean rõ ràng. Không tự công bố gói future version hay bản đã promote.
- Mặc định chọn gói theo registeredAt rồi UUID; có packageId để xem gói cụ thể.
- Số input/result là số record lưu trữ; count không chứng nhận accuracy, hash mới
  kiểm lại, model server đang chạy hay snapshot còn phù hợp với hiện tại.
- Chỉ đếm kết quả selected có run SUCCEEDED/http200/completed/checks.passed=true,
  identity package/model/ticker/role khớp và có input đăng ký. Không đếm cache,
  lỗi hoặc role challenger/baseline vào con số selected.
- Cổng development được giữ riêng, không suy thành production eligibility.
- `dataAsOf` giữ string nguồn: thị trường là date, NPAT có timestamp; đây không
  phải publishedAt của toàn bộ báo cáo hay thời điểm model vừa được gọi.
- Native kind `h3` dùng cả cho direction H1–H4; UI đọc horizonQuarters. Score 0/1
  không được đổi thành độ tin cậy 0/100%. Quantile selected có thể là baseline chung.
- NPAT là VND, hợp nhất và riêng quý; không phải lợi nhuận cổ đông mẹ hay YTD.

Trạng thái per-security: NOT_INSTALLED, NO_REGISTERED_PACKAGE,
TICKER_IDENTITY_AMBIGUOUS, NO_REGISTERED_INPUT hoặc RESEARCH_ONLY.
Registry v1 chỉ có ticker, không có exchange/security UUID: nếu ticker trùng
trong master thì không ghép kể cả caller đã chọn sàn. Symbol không tồn tại trả 404.
Không tự lấp input thiếu từ công ty khác hoặc gửi feature tùy ý từ browser.

`publicResultsAvailable=false`, `executionAvailable=false`,
`featureSubmissionAllowed=false`. Không xuất prediction values, raw features,
manifest files, PKL path, request/response run hoặc retrospective outcomes.
Publication reader cho dự đoán Local AI vẫn chờ chính sách native được hoàn thiện;
đây là tích hợp catalog/progress thực, chưa thay reader forecast LLM.

Nếu chưa có hoặc mới áp một phần schema suite, API trả trạng thái NOT_INSTALLED/
catalog rỗng, không gây 500 cho overview. Nhánh API không tự áp migration của nhánh train.

## Client FE và flow

Đọc [hướng dẫn client](../clients/README.md). `clients/user-api.mjs` không có dependency
runtime, `user-api.d.mts` khai báo envelope, UUID, decimal string và các field chính.
GET không gửi token/cookie, không gọi admin client, không tự retry hoặc ép số tiền
thành Number. Chặn base URL có credential/path sai, path traversal và query object;
phân loại HTTP/network/timeout/abort/invalid JSON riêng.

Luồng browser đã kiểm: tìm FPT → hồ sơ → company UUID → chọn report/item/kỳ →
financial matrix → lineage → Local AI progress → overview → render tên công ty/
nguồn/thời điểm/trạng thái; lỗi thiếu codes trả 400 có thể xử lý qua client.
Không gọi POST analyze/predict của FE demo và không xây lại train engine.

## Bằng chứng kiểm thử

- **209 Java unit/contract**, **15 PostgreSQL integration** trong hai schema tạm,
  0 failure/error/skipped; đóng gói thành công. Có test thiếu schema, ticker trùng,
  gói không tồn tại, paging, private field omission, cổng development và exclude failed/cache.
- **4 client tests** pass với fetch mock; strict TypeScript declaration/usage check
  pass. Test type chặn company ID số, thiếu financial codes và admin execute.
- **54 request HTTP** pass, bao phủ 35 GET trên DB thật ở localhost 8093, không
  admin credential, datasource read-only, worker/seed/model tắt; tối đa 570ms/request
  trong lượt đo local, không phải SLA. Có 400/404/405, CORS và bảo vệ admin.
- **Chrome headless thật** pass, 12 request qua client và CORS origin 127.0.0.1:5173,
  không route mock/Authorization/cookie; dữ liệu DB đi đến text render.
  Test ban đầu thiếu Playwright bundled browser; đã dùng Chrome cài sẵn và chạy lại pass.
- Đọc API mới: 11 model, tổng 218 input và 218 selected research result khớp count DB;
  FPT có 11 input. ACB npat_h1/h2 đều 0 input/result, không mượn dữ liệu FPT.
- Báo cáo FPT lấy trong browser vẫn `publishedAt=null`; không gán ngày từ bộ NPAT
  nghiên cứu vào canonical report. Không biến data cleanliness thành PIT certification.

Không chạy lại train/inference/provider hoặc ghi dữ liệu public để tạo bằng chứng.
Runtime Java thử nghiệm và browser/web server của test đã dừng sau nghiệm thu.
Luồng Spring → Python/PKL → ghi 218 dự đoán là bằng chứng của chat Local AI;
đợt này chỉ nghiệm thu reader và client trên kết quả đã lưu. Chưa nghiệm thu UI
user hoàn chỉnh, future outcome hay độ chính xác mô hình ngoài mẫu tương lai.

## Phần còn chờ

1. Chat Local AI hoàn thiện chính sách publication, scope/vintage/metadata và kết quả
   tương lai trước khi nối reader trả prediction values; không chỉ đổi cờ false.
2. FE user dùng client/UUID/envelope hiện tại, render nguồn, mốc thời gian và trạng thái
   riêng; bỏ POST demo và admin key khỏi luồng user. Bản thiết kế chưa là FE đã chạy.
3. Khi registry mở rộng ticker trùng sàn cần contract v2 có security UUID/exchange.
   Outcome evaluator của gói hiện ghi local; chưa coi kết quả hồi cứu là forward accuracy.
