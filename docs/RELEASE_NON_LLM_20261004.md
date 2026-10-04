# Đợt tách commit backend không bao gồm LLM — 04/10/2026

## Phạm vi

Repository: `Lamprro/HE_THONG_DATA_TAI_CHINH_BACKEND_TEST`, nhánh `master`.
Mốc ban đầu: `918a253`. Đây là đợt đưa lên Git các thay đổi độc lập đã qua
build và kiểm thử hồi quy, không phải xác nhận toàn bộ hệ thống đủ điều kiện production.
Không thay đổi repository Python trong đợt này.

## Các nhóm thay đổi

| Nhóm | Commit | Nội dung | Kiểm thử tích lũy |
| --- | --- | --- | --- |
| Chọn nguồn ingestion | `eb3bd7c` | Cho phép chỉ định `dataSourceCode`; từ chối nguồn không phải API và provider có nhiều nguồn không rõ lựa chọn; cập nhật metadata nguồn. | 59 đạt |
| Giá cổ phiếu | `7c8bec3` | QUOTE là snapshot; OHLCV theo ngày Việt Nam; xử lý đơn vị legacy và bản ghi trùng/xung đột; bộ lọc đọc giá `1d`/`snapshot`. | 71 đạt |
| Chỉ số thị trường | `6b99e43` | Ưu tiên ngày giao dịch, chuẩn hóa nến ngày về đầu ngày Việt Nam, hỗ trợ định dạng ngày của nguồn. | 77 đạt |
| Đọc chỉ tiêu báo cáo tài chính | `0300054` | Bỏ qua nhãn rỗng, dùng nhãn tiếng Việt; có mã dự phòng theo ID nguồn nếu nhãn không tạo được mã ASCII. Không thay đổi cơ chế lưu/revision. | 79 đạt |

## Phương pháp xác nhận

- Mỗi nhóm được đưa vào index bằng danh sách file cụ thể; không đưa cả working tree vào commit.
- Xuất đúng nội dung index sang thư mục kiểm thử riêng trong `target/release-*/`.
  Nhờ vậy các file LLM chưa commit và cấu hình chứa khóa local không thể che giấu phụ thuộc bị thiếu.
- Chạy Maven test trên từng bản xuất; loại `FinancialDataApplicationTests` vì test này
  khởi động application context phụ thuộc cấu hình database thật.
- Đây là kiểm thử unit/regression với fixture và mock, **không phải** gọi Python/Gemini thật
  hoặc chạy lại toàn bộ pipeline trên database production. Không dùng kết quả này để khẳng định dữ liệu DB đã sạch.
- Kiểm tra diff/whitespace và giữ cấu hình bí mật ngoài commit. Push tuần tự, không force push.

## Các phần chủ động giữ local

- Toàn bộ LLM, prompt, forecast, recovery URL/PDF, Gemini, Cloudinary và API quản trị liên quan.
- NEWS workflow/validation/persistence đang thay đổi; không tách vội các phần có phụ thuộc chéo với LLM.
- Thay đổi chọn bản canonical/revision của báo cáo tài chính: cần kiểm chứng replay dữ liệu cũ
  không thay thế nhầm phiên bản mới.
- Các runner sửa lịch sử, migration database, scheduler, seed/catalog và chỉnh sửa khác chưa đủ kiểm chứng độc lập.
- Frontend, bản checkout Python, tài liệu đang sửa khác, cấu hình local và các khóa truy cập.

## Điều kiện cần kiểm tra trước khi triển khai server

1. Kiểm thử tích hợp trên môi trường staging với đúng schema và cấu hình server.
   Đợt này không chạy migration hoặc sửa dữ liệu nghiệp vụ.
2. Giá QUOTE mới dùng `snapshot`; dữ liệu cũ gắn interval khác không tự được chuyển đổi.
   Bộ lọc `snapshot` không đồng nghĩa đã bao phủ các snapshot lịch sử mang nhãn sai.
3. Dữ liệu ngày mới được chuẩn hóa về đầu ngày Việt Nam. Bản ghi lịch sử có timestamp
   lệch giờ cần đối soát khóa trùng/canonical trước khi replay; commit parser không tự dọn lịch sử.
4. Đối chiếu contract đơn vị của nguồn đang chạy: payload `market_price.v1` không được nhân đơn vị lần nữa;
   nguồn legacy phải trả đúng đơn vị theo contract mà parser hỗ trợ.
5. Provider có nhiều nguồn active sẽ bị từ chối nếu không truyền `dataSourceCode`.
   Cập nhật client/admin gọi manual ingestion để chọn nguồn rõ ràng.
6. Xác nhận quyền admin, các scheduler đang bật, cơ chế rollback và log trên server riêng;
   đợt tách commit không chứng nhận các phần này đã hoàn tất.

Các file chưa commit vẫn được giữ nguyên để tiếp tục kiểm chứng ở đợt sau.
