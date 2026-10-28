# API nguồn giá và chỉ số — 25/10/2026

Tiếp tục `feature/user-api`; không merge sang main, không deploy. Đọc thiết kế
user web v4 ngày 24/10 và tiến độ chat Local AI. Bộ train đã được chat phụ trách
đưa lên repo riêng; lượt này không sửa folder train, PKL, manifest hoặc runtime AI.

## Hợp đồng mới

Tổng 37 GET, OpenAPI version `2026-10-25`. Hai endpoint phục vụ màn nguồn U27/U28:

- `GET /api/v1/securities/{symbol}/prices/{id}/lineage?exchange=HOSE`
  nhận ID từ `prices.items[].id` hoặc `quote.data.daily.id`/`snapshot.id`.
  Trả đúng bản ghi đã chọn, OHLCV, ngày giao dịch theo Việt Nam, interval, priceBasis,
  currency/volumeUnit, nguồn, version/activation và fetched/stored timestamps.
- `GET /api/v1/securities/{symbol}/metrics/{id}/lineage?exchange=HOSE`
  nhận UUID từ `metrics.items[].id`. Trả giá trị/đơn vị, ngày chỉ số, kỳ nếu có,
  nguồn/version, PROVIDER hoặc DERIVED, calculationVersion và catalogFormula.

Giá yêu cầu canonical, version ACTIVATED và đúng security; metric theo cùng
company và gate canonical VALID/WARNING như danh sách. Không thay ID không hợp lệ
bằng bản mới nhất. ID sai quyền sở hữu, bản ẩn hoặc không tồn tại trả 404;
ID giá không dương/sai định dạng/vượt long trả 400; trùng symbol chưa chọn sàn trả 409.
ID bigint và số tiền giữ string trong JS. OpenAPI long path ID là string để tránh
client sinh mã làm tròn ID > 2^53. Client thêm `priceLineage` và `metricLineage`.

`catalogFormula` là định nghĩa hiện tại, không chứng minh công thức vendor hay bản
tính lịch sử. `asOfDate` không phải ngày công bố đã xác minh. Derived có thể không
có version/raw trực tiếp; null được giữ nguyên. Không xuất input_snapshot, raw body,
headers hay parameters. URL nguồn có credentials/query/fragment bị lược bỏ.
Không gán tài liệu công bố mới vào số canonical khi chưa đối chiếu phiên bản.

## Kiểm thử thực tế

- 209 Java unit/contract đạt, build/package đạt. Sau sửa schema long ID đã chạy lại
  3 MVC tests và package; kiểm ID `9007199254740993` còn nguyên và overflow trả 400.
- 16 PostgreSQL tests đạt trong schema tạm (13 user + 3 Local AI). Thêm kiểm tra
  exact ID, rejected version, ownership khác công ty/security, derived null source,
  URL không an toàn và không lộ raw. Không fixture vào public.
- 4 client tests và strict TypeScript đạt; mock fetch chỉ dùng ở unit test.
- 59 HTTP request đạt, bao phủ đủ 37 GET trên localhost8093 + DB thật read-only.
- Chrome thật: 16 request, client → CORS → Java → DB → render; kiểm nguồn giá và
  metric khớp đúng ID, số tài chính dạng string, company UUID và Local AI research gate.
  Browser harness là kiểm tích hợp client, chưa phải ứng dụng user đầy đủ.

Không gọi provider/train/inference, không chạy migration/backfill. Local AI vẫn
RESEARCH_ONLY; reader công bố prediction chưa mở. Chat UI v4 có 27 màn prototype,
chưa phải FE runtime; hội thoại/tài khoản/watchlist server chưa có hợp đồng trong
37 GET. Các API user không bị đưa vào main cùng đợt sửa admin.

Bằng chứng local: target/oct25-unit.log, oct25-db.log, oct25-contract.log,
oct25-http.json, oct25-browser.log. Runtime/browser kiểm thử được dừng sau nghiệm thu.
