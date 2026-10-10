# Luồng FINANCIAL_METRIC hợp nhất — 10/10/2026

`RATIO → raw validation → FINANCIAL_METRIC/ACTIVE → FINANCIAL_METRIC_BUILD → financial_metrics`.
Dispatcher sử dụng FinancialMetricWorkflowService và FinancialMetricBuildPersistenceService
của enrichment: transaction toàn batch, input_snapshot/calculation_key, raw/version và
canonical theo ưu tiên nguồn/thời điểm. FinancialMetricBuildService là compatibility
adapter sang cùng workflow; không có writer thứ hai.

`FINANCIAL_METRIC_CALCULATE` gọi HistoricalFinancialMetricService, cùng calculator với
API admin `/api/admin/financial-data/metrics/recalculate`. Tính 5 tỷ lệ balance-sheet và
NET_MARGIN/GROSS_MARGIN/OPERATING_MARGIN từ một báo cáo actual canonical ACTIVATED;
giữ đơn vị VND, lineage, input IDs/values và idempotency. Không trộn báo cáo/source/scope.
Hiện tính các kỳ Q1–Q4; báo cáo năm vẫn được giữ nhưng không tạo thêm tỷ lệ năm bằng job này.

Job chấp nhận symbol (bỏ trống: các mã có báo cáo canonical), startDate (mặc định
2016-01-01), endDate (mặc định hôm nay Việt Nam). Mỗi security có transaction và lock
của calculator; lỗi sau một security được audit FAILED, replay idempotent cho phần
đã commit. Không gọi LLM/Python để sinh tỷ lệ và không biến thiếu denominator thành 0.

Catalog có đủ 8 tỷ lệ actual và 6 provider metrics; hai OCF definitions chỉ là catalog,
chưa có calculator khi chưa chứng minh tương thích kỳ cash-flow/income.
Provider snapshot RATIO không được coi là lịch sử 10 năm. Parser legacy giữ cho
compatibility/contract tests; writer runtime chỉ chấp nhận contract VNDirect đã kiểm chứng.

Seeding và scheduler mặc định tắt. Migration/repair scripts là thao tác riêng,
không tự chạy khi merge hoặc khởi động. Các job BUILD/CALCULATE mới được seed inactive;
admin cần review và kích hoạt qua API trước vận hành tự động.
