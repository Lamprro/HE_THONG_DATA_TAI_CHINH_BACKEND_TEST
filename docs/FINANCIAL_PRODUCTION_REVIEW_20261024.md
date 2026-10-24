# Kiểm tra dữ liệu tài chính và chuẩn bị triển khai

Cập nhật ngày 24/10/2026, giờ Việt Nam. Báo cáo dành cho chủ dự án và người làm FE.

## Kết luận

Python đã triển khai trên Vercel phiên bản 0.7.2, commit `a5bb3c4`. Spring Boot đã chạy thử profile production cục bộ, kiểm tra schema thật và gọi thành công bốn nguồn công bố qua Vercel. Đã sửa luồng chung, bổ sung metadata, liên kết phiên bản giá trị và sửa lỗi lưu nhiều kỳ forecast.

Chưa thể chứng nhận toàn bộ hệ thống và dữ liệu train sẵn sàng production. Kho canonical có 3.900 báo cáo của 25 mã còn thiếu ngày công bố và scope được xác minh ở cấp phiên bản giá trị. Việc có tài liệu chính thức không đủ để tự điền metadata vào mọi số liệu vendor. Đợt này không train lại, không thay baseline và không promote mô hình.

## Những thay đổi đã hoàn thành

- Nguồn công bố được quản lý chung cho FPT, HPG, VNM và ACB, có kiểm tra domain, mã công ty, kỳ, scope, ngày và nguồn raw.
- Metadata giữ phiên bản parser và bản ghi nguồn bất biến. Refresh lặp không tạo bản ghi trùng; API danh sách và coverage đếm tài liệu duy nhất, không cộng lặp phiên bản parser.
- Bảng `financial_value_bindings` liên kết từng giá trị đã kiểm chứng với tài liệu, trang, SHA và phiên bản số liệu; phát hiện liên kết cũ khi giá trị nguồn thay đổi. Đã lưu 37 liên kết FPT, gồm 29 khớp và 8 chênh lệch với tài liệu gốc.
- Bảng `financial_reconciliation_evidence` lưu sáu đối chiếu bổ sung từ tài liệu chính thức. Đây là bằng chứng đọc tài liệu bằng trợ lý, chưa phải chứng nhận thủ công của chuyên viên kế toán; không tự mở quyền dùng làm nhãn train.
- Lỗi forecast thiếu kỳ dự báo đã sửa. Kết quả phân biệt theo mã, tác vụ, quý đích và nhóm chỉ tiêu. Hai horizon không ghi đè nhau; chạy lại mốc dữ liệu cũ không thay kết quả có mốc mới hơn. Đã sửa biên ngày cuối quý, kể cả chuyển năm.
- Profile production kiểm tra cấu hình token, CORS chính xác và schema DB khi khởi động; bảo vệ toàn bộ `/api/**` trừ health. Đã sửa bảy trường CHAR trong sáu entity cho khớp schema thực tế. Docker chạy bằng tài khoản không phải root; thêm CI cho hai repo.

## Đối chiếu tám chênh lệch FPT

| Kỳ | Bằng chứng và kết quả |
| --- | --- |
| 2022Q2 | Khớp cột so sánh quý II trong báo cáo 2023. |
| 2024Q2 | Khớp cột so sánh quý II trong báo cáo 2025. |
| 2024Q4 | Khớp cột so sánh quý IV trong báo cáo 2025. |
| 2025Q2 | Khớp cột so sánh quý II trong báo cáo 2026. |
| 2026Q2 | 5.047.128.933.102 − 2.476.789.833.481 = 2.570.339.099.621 đồng, đúng số VNDirect. Đây là bán niên soát xét trừ quý I, không phải cột quý II trực tiếp. |
| 2025Q4 | 11.232.339.450.734 − 8.237.374.633.637 = 2.994.964.817.097 đồng. VNDirect là 2.994.964.817.096, còn lệch −1 đồng. Giải thích được phần lớn chênh lệch 6,816 tỷ đồng nhưng chưa khớp tuyệt đối. |
| 2017Q2 | Vendor cao hơn tài liệu gốc và cột so sánh năm sau 11 đồng; chưa xác định nguyên nhân. |
| 2020Q2 | Vendor thấp hơn tài liệu gốc và cột so sánh năm sau 1 đồng; chưa xác định nguyên nhân. |

Tổng cộng 5/8 trường hợp có giải thích số học khớp chính xác. Ba trường hợp còn dư 11 đồng, 1 đồng và 1 đồng. Không tự kết luận đó là lỗi làm tròn, không sửa số vendor để ép khớp. Các phép trừ lũy kế vẫn cần kiểm tra tính nhất quán về phạm vi hợp nhất và điều chỉnh hồi tố trước khi dùng làm nhãn quý.

Tài liệu bán niên 2026 còn nêu thay đổi cách hạch toán FPT Telecom từ đầu năm 2026. Khi train cần đặc trưng/cờ thay đổi phạm vi hợp nhất; chỉ nhãn `CONSOLIDATED` chưa đủ bảo đảm chuỗi qua các năm có cùng phạm vi.

## Dữ liệu ngoài FPT

| Mã | Tài liệu duy nhất trong DB sau đợt này | Phạm vi đã gọi |
| --- | ---: | --- |
| FPT | 121 | Danh sách công bố hiện tại, gồm quý, bán niên và năm. |
| HPG | 173 | Trang 1–24; trang 24 trả không còn trang tiếp theo. |
| VNM | 78 | Năm 2020–2026. |
| ACB | 20 | Phần trang hiển thị của các năm 2024–2026; chưa đủ toàn bộ phân trang. |

Tổng 392 tài liệu. Đây là số tài liệu tìm thấy, không phải số báo cáo canonical đã đạt chuẩn train.

ACB 2020–2023 trả lỗi khi refresh qua adapter hiện tại. Điều tra HTML và mã frontend công khai xác định các trang cũ dùng danh sách tải bằng JavaScript. API công khai năm 2023 có `published_start=null`; trang tải không hiển thị ngày công bố. Không dùng `created_at` hoặc `updated_at` làm ngày phát hành tài chính. Cần nguồn công bố bổ sung có ngày, hoặc kiểm tra tài liệu/CBTT tương ứng. Các URL và dữ liệu điều tra nằm trong bằng chứng, không được nhập thành metadata đã xác minh.

21/25 mã chưa có adapter công bố chính thức trong registry. API coverage đã thể hiện giới hạn này. VNM có tài liệu quý IV thực chất chứa báo cáo năm nên `report_kind=UNKNOWN` đến khi kiểm tra cột PDF. Phạm vi hợp nhất, riêng quý/lũy kế, đơn vị và revision phải gắn với đúng giá trị, không chỉ cùng mã và quý.

## Kiểm thử và nghiệm thu thực tế

- Python: 45 tests đạt trên Python 3.12 với lockfile. Unit tests dùng fixture/mock.
- Java: 221 tests đạt, không failure/error/skip trong lần chạy opt-in cuối; 44 tests tích hợp gồm NEWS, audit attempts, forecast và recovery chạy trên schema cô lập. LLM trong các test này là mock, không chứng minh chất lượng dự báo hay quota provider thật.
- Ba test cũ dùng DB chung được loại khỏi lệnh kiểm thử phát hành. CI không có DB private nên các bộ tích hợp opt-in không chạy ở GitHub.
- 18 request thật: HPG, VNM, ACB × CafeF, VNStock, VNDirect × income/balance. Tất cả HTTP 200, raw validation ACCEPTED, parse có kỳ dữ liệu. Không gọi bước promote canonical từ những lần kiểm tra này.
- Profile production thật trên máy hiện tại: Hibernate validate đạt; API thông thường và admin trả 403 khi thiếu token, 200 khi token đúng; bốn refresh FPT/HPG/VNM/ACB qua Vercel đều 200.
- Bổ sung lịch sử: 20 trang HPG và bốn năm VNM thành công; bốn năm ACB bị chặn như mô tả trên. Không tính đợt lịch sử này là hoàn toàn PASS.
- Migration 02/03 và migration kỳ forecast 04 đã thử schema cô lập, chạy lặp rồi rollback trước khi áp DB thật. Dữ liệu đối chiếu được thử riêng, có kiểm tra từ chối SHA/số học sai và replay không thêm bản ghi.

JAR đã đóng gói thành công. Chưa build/run Docker image vì máy không có Docker CLI; chưa triển khai Java lên máy chủ production, chưa nghiệm thu tải cao, nhiều worker, backup/restore hoặc tài khoản FE. Các thay đổi local AI/KBS/FE đang dở ở checkout gốc không nằm trong phạm vi release của nhánh này.

## Luồng dữ liệu cần giữ khi train

Nguồn → Python → adapter Spring → ingestion/raw → validation → parse → đối chiếu tài liệu và phiên bản → tạo dataset đúng thời điểm → chia train/validation/test theo thời gian → so baseline → lưu model được duyệt.

Ngày kết thúc quý cho biết số liệu thuộc kỳ nào. Ngày công bố cho biết thị trường có thể biết số liệu từ lúc nào. Ngày hệ thống quan sát cho biết bản dữ liệu hiện tại được thu thập lúc nào. Ba ngày này không thay thế nhau.

Với số vendor khớp cột so sánh được công bố năm sau, không gắn ngày báo cáo gốc năm trước. `usable_from` của liên kết hiện tại giữ mốc quan sát bảo thủ; truy vấn mốc 2025 không biến tài liệu mới rà soát năm 2026 thành đầu vào sẵn có năm 2025. Đã kiểm tra 37 liên kết không tự được mở cho mốc lịch sử này.

Muốn mở dữ liệu cho train: xác minh ngày/bản gốc hoặc revision, scope và phạm vi hợp nhất, kỳ riêng/lũy kế, đơn vị, chỉ tiêu đúng loại; lưu giá trị theo từng phiên bản và xuất snapshot đúng ngày dự báo. Sau đó mới đo lại coverage, leakage và baseline. Hiện chưa có cổng tự động chứng nhận toàn bộ các bước đó.

## API cho FE

Các route dưới đây cần Bearer token admin. Không đưa token admin dùng chung vào mã JavaScript, localStorage hay biến môi trường được đóng gói vào FE công khai. Cần backend/BFF giữ bí mật hoặc cơ chế đăng nhập và phân quyền người dùng trước khi public FE.

| Route | Chức năng |
| --- | --- |
| `GET /api/admin/financial-data/coverage` | Thiếu metadata theo 25 mã có canonical và mã được hỗ trợ nguồn chính thức. |
| `GET /api/admin/financial-data/status?symbol=FPT` | Tổng hợp báo cáo canonical và tài liệu chính thức. |
| `GET /api/admin/financial-data/disclosures?symbol=HPG&limit=200` | Metadata tài liệu mới nhất, có nguồn và thời điểm quan sát. |
| `POST /api/admin/financial-data/disclosures/refresh` | Body `{"symbol":"HPG","page":2}` hoặc `{"symbol":"VNM","year":2025}`. Dùng `nextPage` để tiếp tục HPG. |
| `POST /api/admin/financial-data/bindings/reconcile` | Body `{"symbol":"FPT"}`; liên kết lại fact đã kiểm chứng, không tự đọc hay xác minh mọi PDF. |
| `GET /api/admin/financial-data/bindings?symbol=FPT&asOf=2025-01-01T00:00:00Z` | So giá trị, phiên bản và trạng thái tại mốc truy vấn. |
| `GET /api/admin/financial-data/reconciliation?symbol=FPT` | Bằng chứng đối chiếu bổ sung, số dư và điều kiện dùng. |

FE nên hiện riêng “đã tìm thấy tài liệu”, “đã đối chiếu giá trị” và “được dùng train”. Không chuyển UNKNOWN thành “đã xác minh”.

## Cấu hình triển khai

Java: `SPRING_PROFILES_ACTIVE=production`, cấu hình datasource private, `FINANCIAL_ADMIN_API_TOKEN` ít nhất 32 ký tự, `APP_CORS_ALLOWED_ORIGINS` là origin HTTPS cụ thể hoặc để trống khi chỉ gọi server. LLM và scheduler tiếp tục opt-in. Hibernate chỉ validate, không tự sửa schema.

Python: đặt `APP_ENV=production` và `PYTHON_SERVICE_TOKEN` riêng ít nhất 32 ký tự. Java dùng cùng `PYTHON_SERVICE_TOKEN` để gọi Python. Health công khai tại `/api/v1/health`. Python production thiếu token phải trả 503 cho API nghiệp vụ. Code hỗ trợ đã kiểm thử; chưa thay đổi secret/env trên Vercel. Bản Vercel hiện tại vẫn có thể gọi API không có token, vì vậy chưa xác nhận ranh giới xác thực production đã bật.

Với DB mới hoặc staging, áp prerequisite của nhánh LLM trước, rồi `V20261021_01`, `V20261024_01`, `02`, `03`, `04`. Xem từng script và precondition; không chạy mù toàn bộ migrations lịch sử. 04 không bịa kỳ cho dữ liệu forecast cũ: nếu thiếu JSON kỳ/target hợp lệ hoặc có bản current trùng, migration phải dừng để xử lý riêng. Sao lưu và thử restore ở môi trường đích trước khi chuyển production.

## Code và bằng chứng

Python main: `2675098` bổ sung nguồn; `a5bb3c4` bảo vệ API, CI và phiên bản 0.7.2.

Java branch `fix/financial-metadata-flow`: `0013434` metadata và liên kết; `d47606b` kỳ forecast; `9be189a` production/schema. PR số 14 vào `feature/llm-processing`, chưa merge master: https://github.com/Lamprro/HE_THONG_DATA_TAI_CHINH_BACKEND_TEST/pull/14

Nguồn chính thức: https://fpt.com/vi/nha-dau-tu/thong-tin-cong-bo ; https://www.hoaphat.com.vn/quan-he-co-dong/bao-cao-tai-chinh ; https://www.vinamilk.com.vn/investor/reports/financial ; https://acb.com.vn/nha-dau-tu/bao-cao-tai-chinh-2023

Bằng chứng cục bộ: `artifacts/financial-production-20261024/` gồm `production-profile/http-smoke.json`, `provider-regression/pipeline_receipts.json`, `history-acceptance/source-calls.json`, `review-inputs.json`, các receipt migration và ảnh PDF đã đọc. Log tests ở `artifacts/financial-provider-repair-20261024/evidence/`; các file này không chứa khóa và không được đẩy toàn bộ lên Git.
