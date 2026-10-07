# NEWS: khôi phục nội dung và tài liệu đính kèm

> Bàn giao cập nhật 07/10/2026: đọc [PROJECT_HANDOVER.md](PROJECT_HANDOVER.md) trước. Code LLM đã push ở `feature/llm-processing` (`5343f81`), chưa merge/deploy. Các số liệu DB, kết quả API và nhận định bên dưới thuộc thời điểm kiểm tra được ghi trong tài liệu; không phải xác nhận runtime ngày 07/10. Implementation và việc còn dở cần đối chiếu với bàn giao mới.

Ngày chốt yêu cầu: 04/10/2026. Đây là bản thiết kế ban đầu. **Đã triển khai nhánh admin recovery; xem [luồng thực tế và kết quả test](NEWS_RECOVERY_FLOW_AND_TESTS.md) để biết trạng thái mới nhất.** Các bảng đề xuất dưới đây KHÔNG được tạo: triển khai tận dụng bảng LLM hiện có.

## Trạng thái hiện tại

Đã có URL Context, downloader có giới hạn, Cloudinary authenticated storage, API duyệt/từ chối và scheduler opt-in. Không thay đổi điều kiện chặn bài thiếu nội dung của luồng phân tích NEWS thông thường. Đã gọi Gemini thật; có trở ngại quyền provider và Cloudinary chưa được nhận diện cấu hình. Chi tiết giới hạn và bằng chứng nằm trong tài liệu cập nhật nêu trên.

## Quy tắc đã thống nhất

- URL-only đi vào nhánh LLM recovery trực tiếp, KHÔNG gọi Python để fetch lại bài.
- Bài nghi thiếu nội dung gửi cả snapshot hiện có và URL sang LLM để đối chiếu.
- Bài chủ yếu ảnh/PDF vẫn vào nhánh LLM đa phương thức. Không upload ảnh vào Cloudinary; không bỏ qua ảnh mang thông tin nghiệp vụ khi phân tích.
- Tài liệu PDF và báo cáo khác được tải có giới hạn rồi lưu Cloudinary. File Word/Excel cần bộ chuyển đổi trước khi phân tích nếu model không hỗ trợ trực tiếp; tuyệt đối không đổi đuôi file để giả PDF.
- Thiếu bằng chứng: PENDING_ADMIN_REVIEW, không ghi đè nội dung hiện hành, không publish phân tích đi kèm.
- Không truy cập được nguồn: giữ URL_ONLY, lưu lý do và giới hạn retry; không suy diễn nội dung.

## Luồng dự kiến

1. Chụp article ID, URL và source hash; claim recovery riêng để không tranh chấp scheduler phân tích.
2. Gemini URL Context đọc đúng URL được chỉ định. Nhận URL tài liệu được tham chiếu; coi mọi URL trong response là dữ liệu chưa tin cậy. Không yêu cầu Python retry bài.
3. Bộ tải Java kiểm tra HTTPS, danh sách miền nguồn được phép, DNS/IP công khai và từng redirect; chặn localhost/private/link-local/metadata endpoint, URL chứa credential. Chống DNS rebinding bằng kiểm soát kết nối/egress, không chỉ kiểm tra URL đầu tiên.
4. Tải theo streaming có giới hạn dung lượng, thời gian, số file, số redirect; kiểm tra MIME thực và chữ ký file. Chặn executable, archive, macro nguy hiểm; kiểm tra malware trước khi lưu/phân tích. Ảnh không vào kho Cloudinary.
5. Tính SHA-256, chống trùng file, upload tài liệu với quyền truy cập hạn chế. Lưu original URL, resolved URL, MIME, kích thước, checksum và Cloudinary asset ID; không ghi key hoặc signed URL hết hạn vào bản ghi công khai. Retry upload phải idempotent, xử lý asset mồ côi nếu DB thất bại.
6. Gửi tài liệu/ảnh nguồn được phép sang Gemini. Cloudinary là kho lưu, không phải bộ đọc báo cáo. Với asset private, backend truyền bytes qua cơ chế file của Gemini; không mở public chỉ để model đọc.
7. Response gồm đánh giá chất lượng, nội dung đề xuất, nguồn/bằng chứng và các phần phân tích. Provider retrieval metadata được lưu độc lập với tự đánh giá của model. Khi phải khám phá link rồi tải file, có thể cần nhiều provider calls trong một tác vụ; không cam kết chỉ một call.
8. Java kiểm tra schema, nguồn, evidence và snapshot; nguồn website/PDF là dữ liệu, không phải chỉ dẫn. Phát hiện prompt injection, thiếu file, truncation, OCR không chắc chắn phải ghi hạn chế.
9. Đạt tiêu chí được định nghĩa: chấp nhận revision và validate phân tích trên đúng revision đó. Chưa đủ bằng chứng: chờ admin. Nội dung cũ và mọi kết quả lịch sử được giữ lại.

## Lưu trữ và duyệt (đề xuất, chưa migration)

- news_article_documents: quan hệ article–document, URL nguồn, asset ID, SHA-256, MIME, size, trạng thái tải/lưu/đọc, thời điểm.
- news_content_revisions: article, base source hash, proposed content, provenance, recovery run, assessment, trạng thái, reviewer và lý do duyệt/từ chối.
- Tiếp tục dùng llm_runs/llm_run_attempts cho các lần gọi AI, validation_rules/validation_results cho kiểm tra, llm_results cho kết quả đã được chấp nhận. Không đưa bản đề xuất chưa duyệt vào API kết quả hiện hành.
- Admin approve phải khóa bài và kiểm tra base hash; nếu nguồn đổi thì trả conflict thay vì đè. Sau approve: hash lại, kiểm tra duplicate, cập nhật quan hệ theo quy tắc hiện tại, làm mất hiệu lực kết quả của revision cũ. Không tạo bài trùng chỉ vì có file hoặc nội dung khôi phục mới.
- Duyệt nội dung không đồng nghĩa duyệt phân tích. Phân tích đính kèm phải qua validation riêng; nếu thất bại có thể chạy lại trên revision đã duyệt.

## Cấu hình Cloudinary

Điền CLOUDINARY_CLOUD_NAME, CLOUDINARY_API_KEY, CLOUDINARY_API_SECRET trong môi trường hoặc application-local.properties (gitignored). Mẫu có ở application-local.properties.example. Không gửi khóa trong chat, không commit khóa. CLOUDINARY_DOCUMENTS_ENABLED mặc định false; bật thiếu khóa sẽ fail startup. Dung lượng mặc định 20 MiB/file, tối đa cấu hình 50 MiB; timeout kết nối 5 giây, đọc 45 giây. Các giới hạn mới chỉ được validate cấu hình, chưa có downloader thực thi.

Việc cung cấp khóa/bật cấu hình chưa kích hoạt pipeline. Kiểm tra gói Cloudinary và chính sách PDF delivery trước khi test thật. Không dùng unsigned upload preset hay public delivery mặc định. Không bật dịch vụ chuyển đổi Office có phí nếu chưa được duyệt.

## Điều kiện nghiệm thu

Test URL-only không gọi Python; URL Context thất bại không tạo phân tích giả; bài ảnh và PDF thực; file khác có converter hoặc UNSUPPORTED; SSRF/redirect/dung lượng/MIME; thiếu Cloudinary key; upload trùng và timeout; admin approve/reject/stale hash; nội dung phục hồi trùng bài khác; citation sai; nguồn đổi khi đang gọi; model fallback thiếu năng lực URL/file; giữ lịch sử và không publish trước duyệt. Test unit phải tách khỏi DB nghiệp vụ; kết luận end-to-end cần API thật và khóa do người dùng cấu hình.

Nguồn kỹ thuật: https://ai.google.dev/gemini-api/docs/generate-content/url-context ; https://ai.google.dev/gemini-api/docs/document-processing ; https://cloudinary.com/documentation/ts_how_to_upload_manage_and_deliver_pdf_files ; https://cloudinary.com/documentation/control_access_to_media
