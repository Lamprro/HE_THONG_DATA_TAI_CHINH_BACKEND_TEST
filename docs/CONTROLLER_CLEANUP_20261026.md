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
