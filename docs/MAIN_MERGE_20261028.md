# Hợp nhất các nhánh backend vào main — 28/10/2026

Theo yêu cầu mới, API user đã được merge vào main; các ghi chú cũ “chưa merge user”
chỉ phản ánh trạng thái lịch sử. Không deploy hoặc chạy migration DB public trong lượt này.

## Các nhánh được hợp nhất

- feature/user-api: 37 GET API, client và controller đã tinh gọn.
- fix/admin-flows, fix/admin-progress: hợp nhất lịch sử; code đã có qua squash #12/#13.
  Giữ các sửa mới hơn trên main về worker, terminal progress, tìm kiếm và ẩn payload.
- feature/forecast-periods-and-health: DB readiness/liveness, kỳ quý, macro coverage,
  tin chỉ URL. Giữ buildCommit và kiểm tra đầy đủ validation round trước công bố.
- fix/financial-metadata-flow: metadata có bằng chứng, tài liệu PDF, đối chiếu số liệu,
  niên độ, lọc dòng thiếu dữ liệu, production config và phân quyền.
- Các nhánh local/remote khác đã là tổ tiên của main; không đưa code cũ đè lên code mới.

## Điểm xử lý khi ghép

Giữ giới hạn theo kỳ/target scope khi thay thế forecast; kết quả historical không
đẩy kết quả cutoff mới hơn khỏi current. Giữ qualityStatus từ validation thay vì gán
WARNING cố định. API user trả thêm forecastPeriodStart, forecastPeriodEnd, targetScope.
Production protect-all vẫn bảo vệ legacy/admin và cho phép namespace public /api/v1;
namespace này chỉ có GET, thử POST trả405. Health giữ buildCommit, có live riêng.

Hai bộ test forecast bổ sung được giữ cùng nhau; schema tạm tắt admin workers.
Bộ test truy cập tài liệu tài chính dùng mock database audit (không gọi DB thật).

## Bằng chứng và giới hạn

- Maven verify/package: 259 unit/contract tests đạt, không lỗi/skip.
- Client Node: 4 tests đạt.
- Snapshot 37 route user vẫn đạt; thêm test bảo vệ production và trường kỳ dự báo.
- Đã thử PostgreSQL schema cô lập cho admin, user, Local AI reader và forecast:
  kết nối cấu hình local timeout (SocketTimeoutException: Connect timed out),
  chưa tạo được schema; không thể nghiệm thu SQL/migration/forecast publication
  tích hợp trong lượt này. Không coi số tests lỗi setup là lỗi nghiệp vụ đã xác định.
- Runtime localhost DB read-only cũng không khởi động vì cùng lỗi kết nối;
  smoke HTTP chưa chạy thành công, không mượn kết quả ngày26 làm kết quả ngày28.
- Không gọi model/provider, không sửa DB public, không sửa folder training hoặc
  các file chưa commit của AI khác ở checkout gốc. Các file chưa commit không phải
  commit trên nhánh nên chưa được đưa vào main; cần chủ phần đó hoàn tất riêng.
- Bằng chứng local: artifacts/merge-main-20261028/unit.log, database.log; các file
  này nằm ngoài checkout main, không đưa log có thể chứa dữ liệu vào Git.
- Workflow deploy vẫn workflow_dispatch; push chỉ chạy CI, không yêu cầu deploy.

## Snapshot nhánh sau hợp nhất

Tất cả ref dưới đây là tổ tiên main tại lúc ghi báo cáo (trước commit tài liệu).
Nhánh cũ giữ nguyên, không xóa hoặc force-push.

| Ref | SHA |
|---|---|
| feat/market-index-pipeline | 6570924 |
| feature/financial-statement-processing | 9246ae6 |
| feature/forecast-periods-and-health | f52fc54 |
| feature/llm-processing | 010dd89 |
| feature/macro-pipeline | f514858 |
| feature/news-processing-flow | 23a4739 |
| feature/stock-price-scenarios | 159425a |
| feature/user-api | 9b82b42 |
| fix/admin | 4e409a4 |
| fix/admin-flows | fd43460 |
| fix/admin-progress | 2367a33 |
| fix/data-enrichment | 8e5aa29 |
| fix/financial-metadata-flow | 9b6b784 |
| fix/financial-metadata-integration | ec15639 |
| fix/training-data-quality | c0df424 |
| integration/data-enrichment | 1dfbf91 |
| integration/financial-metrics | 4412a43 |
| integration/ingestion-history | ba5e436 |
| integration/llm-macro | ec8dd2b |
| integration/main-consolidation | 336565d |
| integration/manual-deploy | 0ce09df |
| integration/stock-price | 361b8af |
| main | 6ffa421 |
| master | f514858 |
| origin | 046fdaf |
| origin/feat/market-index-pipeline | 6570924 |
| origin/feature/financial-metric-calculation | e435659 |
| origin/feature/financial-statement-processing | 9246ae6 |
| origin/feature/forecast-periods-and-health | f52fc54 |
| origin/feature/llm-processing | 010dd89 |
| origin/feature/news-processing-flow | 23a4739 |
| origin/feature/phase1-jpa-job-engine | 866a24b |
| origin/feature/phase1-person1-ingestion | b84db10 |
| origin/feature/phase1-person2-provider-adapter | 8648b72 |
| origin/feature/stock-price-scenarios | 159425a |
| origin/feature/user-api | 9b82b42 |
| origin/fix/admin | 4e409a4 |
| origin/fix/admin-flows | fd43460 |
| origin/fix/admin-progress | 2367a33 |
| origin/fix/data-enrichment | 8e5aa29 |
| origin/fix/financial-metadata-flow | 9b6b784 |
| origin/fix/training-data-quality | c0df424 |
| origin/integration/data-enrichment | 1dfbf91 |
| origin/integration/financial-metrics | 4412a43 |
| origin/integration/ingestion-history | ba5e436 |
| origin/integration/llm-macro | ec8dd2b |
| origin/integration/main-consolidation | 336565d |
| origin/integration/manual-deploy | 0ce09df |
| origin/integration/phase1 | 3b91d2f |
| origin/integration/stock-price | 361b8af |
| origin/main | 046fdaf |
| origin/master | ec15639 |
