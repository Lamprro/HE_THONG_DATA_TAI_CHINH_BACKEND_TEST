# Quy tắc validation NEWS và NEWS_DATA

## Nơi quản lý

- `src/main/resources/validation/news-rules.json`: tên, nhóm, mức lỗi và cấu hình của 14 rule NEWS/NEWS_DATA.
- `ValidationRuleCatalogService.newsDefinitions()`: đọc file trên; `seed()` đồng bộ vào `validation_rules`.
- `ValidationRuleExecutionService.execute()`: chạy method theo `executor_key`.
- `ValidationJobService.domain()`: NEWS/NEWS_COMPANY dùng nhóm NEWS; NEWS_DATA dùng nhóm NEWS_DATA.

Khi runtime chạy, cấu hình lấy từ bảng `validation_rules`. Sửa file JSON cần seed lại;
sửa trực tiếp DB có hiệu lực ở lượt validate mới, nhưng lần seed sau sẽ lấy lại cấu hình trong file.
Thêm executor mới phải bổ sung code Java; JSON không chứa mã thực thi.

## Rule và dữ liệu kiểm tra

| Rule | Nhóm | Bảng.cột / đường dẫn | Mức lỗi |
| --- | --- | --- | --- |
| NEWS_PAYLOAD_STRUCTURE | NEWS | raw_payloads.payload.data: mảng không rỗng, từng phần tử là object | ERROR |
| NEWS_URL_REQUIRED | NEWS | payload.data[i].url/link/href: HTTP(S), có host, không chứa user/password | ERROR |
| NEWS_TITLE_REQUIRED | NEWS | payload.data[i].title/headline: tiêu đề không rỗng | WARNING |
| NEWS_PUBLISHED_AT_VALID | NEWS | payload.data[i].publishedAt: nếu có phải đúng ngày/giờ | WARNING |
| NEWS_SYMBOL_MATCH | NEWS | payload.data[i].symbol đối chiếu raw_payloads.source_symbol | WARNING |
| NEWS_URL_DUPLICATE_IN_BATCH | NEWS | URL lặp trong cùng payload sau chuẩn hóa | WARNING |
| NEWS_DUPLICATE_HASH | NEWS | raw_payloads.checksum_sha256 và data_source_id | WARNING (*) |
| NEWS_DATA_METADATA_REQUIRED | NEWS_DATA | raw_payloads.payload: requested_url/final_url là chuỗi; http_status số nguyên; textual boolean | ERROR |
| NEWS_DATA_URL_VALID | NEWS_DATA | payload.requested_url và payload.final_url: HTTP(S) có host | ERROR |
| NEWS_DATA_HTTP_SUCCESS | NEWS_DATA | payload.http_status: HTTP của trang nguồn từ 200 đến 299 | ERROR |
| NEWS_DATA_CONTENT_TYPE_VALID | NEWS_DATA | raw_payloads.content_type là HTML/XHTML; payload.textual=true | ERROR |
| NEWS_DATA_RAW_TEXT_REQUIRED | NEWS_DATA | raw_payloads.raw_text: không NULL/rỗng/trắng | ERROR |
| NEWS_DATA_HTML_STRUCTURE | NEWS_DATA | raw_text: có dấu hiệu tài liệu HTML và text hiển thị trong body | ERROR |
| NEWS_DATA_BLOCK_PAGE_DETECTED | NEWS_DATA | raw_text: title/h1 chứa dấu hiệu trang lỗi/chặn truy cập | WARNING |

`ERROR`/`CRITICAL` bị FAIL sẽ chặn tạo version. WARNING thông thường không chặn.
(*) NEWS_DUPLICATE_HASH giữ chính sách cũ: FAIL vẫn chặn version dù severity là WARNING.
Không nhầm rule này với URL trùng giữa hai lần lấy danh sách tin.

Các rule chung RAW_ERROR_MESSAGE, RAW_ENVELOPE_REQUIRED và DATA_COUNT_MATCH vẫn áp dụng
theo điều kiện hiện có. Metadata NEWS_DATA không có data/provider/dataset, nên không bắt nó
tuân theo envelope danh sách NEWS. DATA_COUNT_MATCH kiểm tra count là số nguyên, khớp số phần tử.

## Cấu hình và giới hạn

- Ngày CafeF: `dd/MM/uuuu HH:mm`, múi giờ Asia/Ho_Chi_Minh; uuuu là ký hiệu năm dùng với Java strict parsing.
- URL trùng trong batch: chuẩn hóa scheme/host, cổng mặc định, path, fragment và thứ tự query;
  bỏ utm_*, fbclid, gclid theo cấu hình. Giữ các tham số định danh bài như id.
- Không so title/body của NEWS vì NEWS chưa chứa nội dung bài.
- NEWS_DATA đọc HTML từ raw_text; không đổi cách lưu dữ liệu hoặc mapping bài báo.
- HTML được parse cục bộ, không thực thi script hay gọi URL. Kiểm tra cấu trúc không chứng minh
  nội dung đã được trích xuất đúng thành bài báo. Dấu hiệu trang chặn chỉ là cảnh báo.
- Mảng NEWS rỗng hiện bị chặn vì workflow lấy nội dung chưa có nhánh xử lý batch không có link.
- FAIL ghi vị trí phần tử lỗi đầu tiên, ví dụ payload.data[12].url. Kết quả vẫn thuộc cả raw payload.

## Đồng bộ và kiểm tra

`scripts/SyncNewsValidationRules.java --apply` dùng cùng file JSON để thêm/cập nhật đúng 14 rule.
Cần PostgreSQL JDBC và Jackson trên classpath, chạy từ thư mục gốc dự án.
Script đọc kết nối từ application-local.properties, xuất bản sao các rule cũ vào target,
giữ ID của rule đã có, kiểm tra dữ liệu trước COMMIT. `--inspect` chỉ đọc rule.
Không ghi raw_payloads, validation_results hoặc data_versions.

Lần đồng bộ này: thêm 11 rule, cập nhật 3 rule NEWS, gồm đổi NEWS_TITLE_REQUIRED sang WARNING.
Kiểm tra: 44 unit test PASS. Chạy thử executor chỉ đọc trên 2 NEWS và 100 NEWS_DATA:
toàn bộ 714 lần kiểm tra rule theo nhóm đều PASS; không ghi lại kết quả cũ.

Cần khởi động lại/deploy backend với code mới để áp dụng các executor mới.
Raw đã có validation_results không được scheduler tự validate lại khi đổi bộ rule.
Không tự xóa kết quả hoặc đổi trạng thái version lịch sử.
