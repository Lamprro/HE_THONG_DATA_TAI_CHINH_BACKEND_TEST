# Kiểm tra dữ liệu tài chính và chuẩn bị triển khai

Cập nhật ngày 24/10/2026, giờ Việt Nam. Báo cáo dành cho chủ dự án và người làm FE.

## Kết luận

Python đã triển khai trên Vercel phiên bản 0.7.4, commit `8259a5d`. Spring Boot đã chạy thử profile production cục bộ, kiểm tra schema thật và tích hợp sáu nguồn công bố. DB hiện có 613 tài liệu duy nhất, tăng từ 392 ở lần kiểm tra trước. Đã sửa luồng chung, bổ sung metadata, liên kết phiên bản giá trị và sửa lỗi lưu nhiều kỳ forecast.

Chưa thể chứng nhận toàn bộ hệ thống và dữ liệu train sẵn sàng production. Kho canonical có 3.900 báo cáo của 25 mã còn thiếu ngày công bố và scope được xác minh ở cấp phiên bản giá trị. Việc có tài liệu chính thức không đủ để tự điền metadata vào mọi số liệu vendor. Đợt này không train lại, không thay baseline và không promote mô hình.

## Những thay đổi đã hoàn thành

- Nguồn công bố được quản lý chung cho FPT, HPG, VNM, ACB, HSG và EIB, có kiểm tra domain, mã công ty, kỳ, scope, ngày và nguồn raw.
- Metadata giữ phiên bản parser và bản ghi nguồn bất biến. Refresh lặp không tạo bản ghi trùng; API danh sách và coverage đếm tài liệu duy nhất, không cộng lặp phiên bản parser.
- Bảng `financial_value_bindings` liên kết từng giá trị đã kiểm chứng với tài liệu, trang, SHA và phiên bản số liệu; phát hiện liên kết cũ khi giá trị nguồn thay đổi. Đã lưu 37 liên kết FPT, gồm 29 khớp và 8 chênh lệch với tài liệu gốc.
- Bảng `financial_reconciliation_evidence` lưu sáu đối chiếu bổ sung từ tài liệu chính thức. Đây là bằng chứng đọc tài liệu bằng trợ lý, chưa phải chứng nhận thủ công của chuyên viên kế toán; không tự mở quyền dùng làm nhãn train.
- Lỗi forecast thiếu kỳ dự báo đã sửa. Kết quả phân biệt theo mã, tác vụ, quý đích và nhóm chỉ tiêu. Hai horizon không ghi đè nhau; chạy lại mốc dữ liệu cũ không thay kết quả có mốc mới hơn. Đã sửa biên ngày cuối quý, kể cả chuyển năm.
- Profile production kiểm tra cấu hình token, CORS chính xác và schema DB khi khởi động; bảo vệ toàn bộ `/api/**` trừ health. Đã sửa bảy trường CHAR trong sáu entity cho khớp schema thực tế. Docker chạy bằng tài khoản không phải root; thêm CI cho hai repo.

## Các lỗi phát hiện khi mở rộng sang HSG và EIB

HSG có niên độ từ tháng 10 đến tháng 9. Quý III niên độ 2025–2026 kết thúc ngày 30/06/2026; không được ghép với quý III dương lịch kết thúc tháng 9. Parser nguồn công bố và Spring kiểm tra độc lập quy tắc này. Báo cáo năm có ngày kết thúc rõ ràng 30/09 được tính từ 01/10 năm trước. Chưa tự sửa các kỳ canonical lịch sử hoặc dịch chuyển nhãn quý vendor khi chưa có bằng chứng.

CafeF trả số liệu ngân hàng EIB và ACB dưới tên chỉ tiêu doanh nghiệp thông thường. Ví dụ EIB quý II/2026: 4.571.981.000.000 đồng là thu nhập lãi, nhưng bị gắn nhãn doanh thu bán hàng; công thức doanh thu thuần trên trang cũng không khớp. Python hiện từ chối mẫu này. Spring kiểm tra lại công thức cho cả raw cũ trước khi build. Replay raw EIB đã lưu bị chặn đúng; raw HSG hợp lệ vẫn parse được. Dùng VNStock hoặc VNDirect cho hai ngân hàng trong lúc chờ CafeF sửa mẫu; vẫn cần đối chiếu scope, đơn vị và phiên bản.

Tham số `period` của CafeF được sửa mô tả: 0 là báo cáo năm, 1–4 là quý cuối của cửa sổ bốn cột. `period=2` không có nghĩa tự động lấy số lũy kế bán niên. API nay nhận cả quý 3 và 4. Bản cũ gọi mặc định quý 1 có thể trả cửa sổ cũ, nên job phải chọn kỳ cần lấy và kiểm ngày thực tế trong phản hồi.

EIB có ngày phát hành trong trường `published_start`; không dùng ngày tạo/sửa bản ghi CMS để thay thế. Host PDF EIB thiếu chứng chỉ trung gian DigiCert. Đã bổ sung chứng chỉ công khai, xác minh chuỗi với CA chuẩn và vẫn kiểm hostname/TLS. Ba metadata HSG trùng do mã hóa dấu ngoặc URL đã được sao lưu, thử xử lý ở schema cô lập rồi dọn; raw nguồn được giữ nguyên. Gọi lại sáu request cuối không thêm bản trùng.

PDF EIB 15.427.322 byte còn làm lộ lỗi giới hạn chuỗi JSON khi chuyển sang base64. Đã sửa bộ đọc nội bộ cho tài liệu và lớp lưu JSON của Hibernate; không thay giới hạn bộ đọc HTTP thông thường. Giới hạn PDF vẫn là 25 MiB, chuỗi lưu nội bộ tối đa 35 MiB. Pipeline thật đã tải, lưu, đọc lại PDF EIB và HSG với SHA khớp; đây là kiểm chứng lưu tài liệu, chưa phải kiểm chứng từng số liệu trong PDF.

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
| EIB | 111 | Các bản ghi tài chính tiếng Việt có ngày công bố trong danh sách chính thức. |
| HSG | 110 | Trang 1–20; đã sửa tên tệp cũ có dấu gạch dưới. Còn trang lịch sử sau trang 20 chưa nghiệm thu. |

Tổng 613 tài liệu. Đây là số tài liệu tìm thấy, không phải số báo cáo canonical đã đạt chuẩn train. Ngày trên các trang lưu trữ HSG cũ có thể là ngày đăng lại lên website; chưa chứng nhận đó là ngày phát hành đầu tiên. Không dùng các bản lưu trữ này để tự điền ngược ngày cho dataset lịch sử.

ACB 2020–2023 trả lỗi khi refresh qua adapter hiện tại. Điều tra HTML và mã frontend công khai xác định các trang cũ dùng danh sách tải bằng JavaScript. API công khai năm 2023 có `published_start=null`; trang tải không hiển thị ngày công bố. Không dùng `created_at` hoặc `updated_at` làm ngày phát hành tài chính. Cần nguồn công bố bổ sung có ngày, hoặc kiểm tra tài liệu/CBTT tương ứng. Các URL và dữ liệu điều tra nằm trong bằng chứng, không được nhập thành metadata đã xác minh.

19/25 mã chưa có adapter công bố chính thức trong registry. API coverage đã thể hiện giới hạn này. VNM có tài liệu quý IV thực chất chứa báo cáo năm nên `report_kind=UNKNOWN` đến khi kiểm tra cột PDF. Phạm vi hợp nhất, riêng quý/lũy kế, đơn vị và revision phải gắn với đúng giá trị, không chỉ cùng mã và quý.

## Kiểm thử và nghiệm thu thực tế

- Python: 53 tests đạt trên Python 3.12 với lockfile. Unit tests dùng fixture/mock.
- Java: lượt verify cuối có 230 tests đạt, không failure/error/skip. 44 tests tích hợp gồm NEWS, audit attempts, forecast và recovery chạy trên schema cô lập. LLM trong các test này là mock, không chứng minh chất lượng dự báo hay quota provider thật.
- Ba test cũ dùng DB chung được loại khỏi lệnh kiểm thử phát hành. CI không có DB private nên các bộ tích hợp opt-in không chạy ở GitHub.
- Lượt trước: 18 request HPG/VNM/ACB đều qua kiểm tra cấu trúc. Kết quả đó không chứng nhận tên chỉ tiêu đúng; kiểm tra nội dung bổ sung phát hiện mẫu ngân hàng CafeF sai như trên.
- Lượt mới: 18 request HSG/EIB/ACB × ba provider × income/balance. 13 request parse ngay, bốn mẫu CafeF ngân hàng bị chặn đúng, một CafeF HSG lỗi tạm thời; gọi lại HSG qua pipeline thật đã thành công. Tổng 14 trường hợp hợp lệ parse được. Không promote canonical từ những lần kiểm tra này.
- Profile production thật trên máy hiện tại: Hibernate validate đạt; API thông thường và admin trả 403 khi thiếu token, 200 khi token đúng; bốn refresh FPT/HPG/VNM/ACB qua Vercel đều 200.
- Bổ sung lịch sử: 20 trang HPG và bốn năm VNM thành công; bốn năm ACB bị chặn như mô tả trên. Không tính đợt lịch sử này là hoàn toàn PASS.
- Migration 02/03 và migration kỳ forecast 04 đã thử schema cô lập, chạy lặp rồi rollback trước khi áp DB thật. Dữ liệu đối chiếu được thử riêng, có kiểm tra từ chối SHA/số học sai và replay không thêm bản ghi.
- Bổ sung migration 05 cho EIB/HSG và 06 dọn ba metadata URL trùng, đều có rehearsal trước apply. Vercel 0.7.4 đã qua refresh EIB/HSG và kiểm tra HTTP production 403/200; sáu refresh cuối đều 200, insertedDocuments bằng 0.

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
| `POST /api/admin/financial-data/disclosures/refresh` | Body `{"symbol":"HSG","page":2}` hoặc `{"symbol":"VNM","year":2025}`. Dùng `nextPage` để tiếp tục HPG/HSG. |
| `POST /api/admin/financial-data/bindings/reconcile` | Body `{"symbol":"FPT"}`; liên kết lại fact đã kiểm chứng, không tự đọc hay xác minh mọi PDF. |
| `GET /api/admin/financial-data/bindings?symbol=FPT&asOf=2025-01-01T00:00:00Z` | So giá trị, phiên bản và trạng thái tại mốc truy vấn. |
| `GET /api/admin/financial-data/reconciliation?symbol=FPT` | Bằng chứng đối chiếu bổ sung, số dư và điều kiện dùng. |

FE nên hiện riêng “đã tìm thấy tài liệu”, “đã đối chiếu giá trị” và “được dùng train”. Không chuyển UNKNOWN thành “đã xác minh”.

## Cấu hình triển khai

Java: `SPRING_PROFILES_ACTIVE=production`, cấu hình datasource private, `FINANCIAL_ADMIN_API_TOKEN` ít nhất 32 ký tự, `APP_CORS_ALLOWED_ORIGINS` là origin HTTPS cụ thể hoặc để trống khi chỉ gọi server. LLM và scheduler tiếp tục opt-in. Hibernate chỉ validate, không tự sửa schema.

Python: đặt `APP_ENV=production` và `PYTHON_SERVICE_TOKEN` riêng ít nhất 32 ký tự. Java dùng cùng `PYTHON_SERVICE_TOKEN` để gọi Python. Health công khai tại `/api/v1/health`. Python production thiếu token phải trả 503 cho API nghiệp vụ. Code hỗ trợ đã kiểm thử; chưa thay đổi secret/env trên Vercel. Bản Vercel hiện tại vẫn có thể gọi API không có token, vì vậy chưa xác nhận ranh giới xác thực production đã bật.

Với DB mới hoặc staging, áp prerequisite của nhánh LLM trước, rồi `V20261021_01`, `V20261024_01` đến `06`. Xem từng script và precondition; không chạy mù toàn bộ migrations lịch sử. 04 không bịa kỳ cho dữ liệu forecast cũ: nếu thiếu JSON kỳ/target hợp lệ hoặc có bản current trùng, migration phải dừng để xử lý riêng. 06 chỉ dọn các bản trùng xác định trong đợt backfill này. Sao lưu và thử restore ở môi trường đích trước khi chuyển production.

## Code và bằng chứng

Python main: `3d6dbdf` nguồn EIB/HSG; `e364e22` chặn mẫu CafeF sai; `8259a5d` TLS EIB, định danh URL HSG và phiên bản 0.7.4. Các sửa trước đó vẫn nằm trong lịch sử commit.

Java branch `fix/financial-metadata-flow`: `b1c9bd4` nguồn EIB/HSG; `d472ead` kiểm tra CafeF và niên độ, kế tiếp các sửa metadata/forecast/production trước đó. PR số 14 vào `feature/llm-processing`, chưa merge master: https://github.com/Lamprro/HE_THONG_DATA_TAI_CHINH_BACKEND_TEST/pull/14

Nguồn chính thức: https://fpt.com/vi/nha-dau-tu/thong-tin-cong-bo ; https://www.hoaphat.com.vn/quan-he-co-dong/bao-cao-tai-chinh ; https://www.vinamilk.com.vn/investor/reports/financial ; https://acb.com.vn/nha-dau-tu/bao-cao-tai-chinh-2023

Bằng chứng cục bộ: `artifacts/financial-production-20261024/` gồm `production-profile/http-smoke.json`, `provider-regression/pipeline_receipts.json`, `history-acceptance/source-calls.json`, `review-inputs.json`, các receipt migration và ảnh PDF đã đọc. Log tests ở `artifacts/financial-provider-repair-20261024/evidence/`; các file này không chứa khóa và không được đẩy toàn bộ lên Git.

Bằng chứng bổ sung: `artifacts/financial-production-followup-20261024/`, gồm `vercel-final`, `financial-final`, `financial-retry`, `raw-replay.json`, các receipt migration, sao lưu metadata trùng và log kiểm thử. Đây là kết quả có ngày của đợt hiện tại; không dùng kết luận của snapshot cũ để bỏ qua các lỗi nội dung mới phát hiện.
