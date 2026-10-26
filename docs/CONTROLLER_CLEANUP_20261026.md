# Tinh gọn controller — 26/10/2026

Làm main trước theo yêu cầu. Năm controller nhỏ gộp thành hai lớp:

| Lớp chung | Các phần được gom |
|---|---|
| MarketDataAdminController | MarketPriceAdminController, MarketIndexAdminController, MacroAdminController |
| FinancialDataAdminController | HistoricalFinancialMetricAdminController, TrainingDataQualityAdminController |

Thư mục controller/admin giảm 12 → 9 class. Giữ 10 URL, HTTP method, request body,
tham số/default, response, 201 khi seed index và giới hạn phân trang cũ. Phân trang
market dùng helper chung; hai endpoint tài chính dùng chung authorize. Interceptor
admin vẫn bảo vệ toàn bộ URL như cũ.

Catalog tự sinh sẽ hiển thị tên controller mới; operation seed macro đổi thành
seedMacroJobs để phân biệt với index. ID catalog METHOD+PATH không đổi và FE
không ràng buộc vào tên class. Không thêm lớp wrapper hoặc giữ class cũ rỗng.

Giữ service riêng khi có transaction, lock, worker hoặc nghiệp vụ độc lập. Không
gộp bean transaction vào chính caller làm mất proxy/self-invocation boundary.
Không sửa pipeline provider, persistence, Local AI train, entity/schema hoặc FE.

Kiểm thử:
- Clean build để loại bytecode controller đã xóa; 203 unit/contract đạt, package đạt.
- Test HTTP MVC đủ 10 route: auth trước gọi service, defaults/clamp, native dispatch,
  body và HTTP201. Domain service mock, không phải chạy provider thật.
- 12 HTTP localhost thật đạt: 6 GET nghiệp vụ với DB read-only, runtime/catalog,
  4 POST không token trả403 trước khi xử lý. Không thực hiện seed/recalculate/review
  trên DB public. Script scripts/admin_data_routes_smoke.py để kiểm lại.
- Không chạy lại PostgreSQL fixture suite vì SQL/domain services không thay đổi.

Bằng chứng local ở artifacts/refactor-20261026/main-unit.log và main-http.json
(ngoài checkout này). Chỉ commit/push main, không deploy. Nhánh user được tinh gọn
sau commit main, không đưa API user vào main. Checkout LLM và thư mục train giữ nguyên.

## Nhánh user sau main

Main đã commit/push `046fdaf` trước; merge một chiều main vào feature/user-api
(`17c12d4`), không đưa API user về main. Controller user giảm 12 → 6 lớp:
- UserMarketController: thị trường, giá, screener, filters, giao dịch ngoại,
  reference observations và nguồn giá.
- UserFinancialController: báo cáo, chỉ số, ma trận, so sánh, kỳ dữ liệu và nguồn tài chính.
- UserSecurityController: mã, công ty, tổng quan và tình trạng dữ liệu.
- News, Local AI và contract giữ nhóm nghiệp vụ riêng.

Xóa sáu controller nhỏ, không giữ wrapper. Các method tiếp tục gọi đúng service cũ;
không thay SQL, validation, transaction hoặc quy tắc công bố kết quả AI. Annotation
EndpointId lồng trong contract giữ operationId cũ khi di chuyển handler, tránh làm
hỏng SDK sinh từ hợp đồng đã phát hành. Tham số, defaults và kiểu response giữ nguyên.

Nghiệm thu 26/10/2026:
- Clean verify/package đạt; 213 unit/contract tests, không lỗi hoặc bỏ qua.
- Snapshot hợp đồng trước refactor bằng toàn bộ OpenAPI sau refactor (37 route),
  kiểm cả MVC và runtime localhost. Snapshot chỉ là schema API, không chứa data giả.
- Native HTTP smoke: 59 requests / 37 route đạt với DB thật chỉ đọc.
- Chrome/client: 16 requests đạt, không mock, đọc được công ty, giá, ma trận và
  trạng thái nghiên cứu; không gọi model và không dùng token admin.
- Không chạy lại PostgreSQL fixture suite; không thay SQL/services, không chạy
  job ghi dữ liệu, training hoặc provider. Workers/schedulers tắt trên runtime test.

Bằng chứng local user-unit.log, user-http.json và user-contract-before.json ở
artifacts/refactor-20261026 ngoài checkout. Chỉ push nhánh feature/user-api;
không merge vào main, không deploy.
