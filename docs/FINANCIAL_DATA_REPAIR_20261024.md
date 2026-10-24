# Sửa nguồn tài chính và chuẩn bị API cho FE

Cập nhật ngày 24/10/2026, giờ Việt Nam. Python nằm ở repo
`Lamprro/HE_THONG_DATA_TAI_CHINH_TEST`, Java ở
`Lamprro/HE_THONG_DATA_TAI_CHINH_BACKEND_TEST`. Đây là đợt sửa nguồn dữ liệu,
không phải chứng nhận độ chính xác hay phát hành mô hình local AI.

## Kết quả đã kiểm tra

- CafeF: đọc đúng bảng tiêu đề kỳ và bảng chỉ tiêu, giữ tên/mã nguồn và số chính xác.
  Ô thiếu giữ thiếu; lỗi cấu trúc bảng bị từ chối. Không nhân giá trị với đơn vị trên
  widget hiển thị vì widget và số thô chưa được xác minh cùng thang đo.
- VNStock: cài SDK 4.0.9 và vnai 2.6.3 từ kho chính thức, khóa dependencies bằng
  `uv.lock`. Java bỏ qua dòng nhiều kỳ nhưng toàn bộ ô trống, vẫn giữ giá trị 0 thật.
- Thêm nguồn FPT IR: lấy ngày từ `actualPublishedAt`, scope từ tiêu đề từng tài liệu,
  ID phiên bản nguồn, URL, SHA và raw. Không dùng ngày kết thúc quý làm ngày công bố.
- DB đã kết nối lại; migration được thử hai lần trong schema riêng, kiểm 4 constraint
  âm, rollback, sau đó áp public. Đã thêm 88 tài liệu; gọi lặp không tạo bản ghi mới.
- Bản Vercel `0.7.1`, commit `25fdde9bc05ab1fab5dd9a0c776447734f8ba281` đã chạy.
  Build đầu bị thiếu index của vnai, đã sửa và build sau thành công.

Luồng nghiệm thu: Java Spring -> adapter hiện có -> Vercel -> provider thật ->
raw/audit trong DB -> validation -> parser. Không dùng fetcher thay thế.

| Nguồn | Request | HTTP/validation/parser | Số kỳ đọc được theo KQKD/CĐKT/LCTT |
|---|---:|---|---|
| CafeF | 3 | 200 / ACCEPTED / PASS | 4 / 4 / 3 |
| VNStock | 3 | 200 / ACCEPTED / PASS | 4 / 4 / 1 |
| VNDirect | 3 | 200 / ACCEPTED / PASS | 1 / 1 / 1 |

Số kỳ là dữ liệu thực nhận ở request kiểm thử, không phải chứng nhận đã đủ lịch sử.
32 Java tests và 36 Python tests PASS; Python được kiểm cả môi trường 3.12 dùng
lockfile và môi trường 3.13. Unit tests có fixture/mock; các lần gọi kể trên là thật.
API HTTP Java được kiểm với DB và Vercel thật: GET thiếu quyền 403, GET có quyền 200,
POST refresh 200. Không bật scheduler, không chạy build canonical hàng loạt.

## Đối chiếu 8 khoản lệch FPT

Đã gọi lại đúng 8 kỳ VNDirect, tải lại đủ 8 PDF qua Spring/proxy, so SHA và đọc trực
tiếp các trang KQKD. Đọc thêm bản lưu 2023Q2 để đối chiếu cột so sánh 2022. Tất cả tám PDF giữ nguyên SHA; số vendor mới vẫn như lần kiểm trước.
Đơn vị chênh lệch trong bảng là đồng, tính bằng VNDirect trừ tài liệu của chính kỳ đó.

| Kỳ | Chênh lệch | Kết luận có bằng chứng |
|---|---:|---|
| 2017Q2 | +11 | Chưa rõ nguyên nhân; không tự coi là làm tròn |
| 2020Q2 | -1 | Chưa rõ nguyên nhân; không tự coi là làm tròn |
| 2022Q2 | -49 | Số vendor khớp cột so sánh 2022 trong báo cáo 2023Q2 |
| 2024Q2 | +4.672.145.849 | Số vendor khớp cột so sánh 2024 trong báo cáo 2025Q2 |
| 2024Q4 | +7.344.008.580 | Số vendor khớp cột so sánh 2024 trong báo cáo 2025Q4 |
| 2025Q2 | +29.322.825 | Số vendor khớp cột so sánh 2025 trong báo cáo 2026Q2 |
| 2025Q4 | +6.816.254.542 | Còn cần tài liệu/bản điều chỉnh giải thích |
| 2026Q2 | -67.021.994 | Còn cần tài liệu/bản điều chỉnh giải thích |

Bốn khoản đã xác định là **khác phiên bản giá trị**: số cập nhật ở tài liệu sau khác
số trong tài liệu của kỳ ban đầu. Không kết luận thời điểm vendor cập nhật bằng
ngày công bố tài liệu sau; đó mới là bằng chứng giá trị tồn tại ở tài liệu này.
Không sửa số PDF cho giống vendor, không gắn ngày bản cũ cho số đã cập nhật.
Bốn khoản còn lại vẫn mở. Mọi đối chiếu tiếp tục `training_eligible=false`.

## API cho FE

Prefix Java: `/api/admin/financial-data`. Dùng cơ chế admin Bearer hiện có,
cấu hình `financial.admin.api-token` tối thiểu 32 ký tự; chưa cấu hình trả 503,
không có/sai quyền trả 403. Không hard-code token vào mã FE công khai.

1. `GET /status?symbol=FPT`: trả `canonical` với số báo cáo thiếu ngày/scope/revision/
   đơn vị, `officialDocuments`, `trainingReadiness`, `supportedOfficialIssuers` và
   `serverTime`. Đây là trạng thái chất lượng, không phải dự đoán của model.
2. `GET /disclosures?symbol=FPT&limit=100`: danh sách `id`, `rawPayloadId`, `observedAt`,
   `document`. Giới hạn 1–200; sắp theo kỳ và ngày công bố giảm dần. Mỗi document có
   `period_end`, `published_on`, `available_at`, `report_scope`, `revision_status`,
   `report_version_id`, `document_url`, SHA và các evidence nguồn.
3. `POST /disclosures/refresh`, body `{"symbol":"FPT","year":2025}`. Bỏ year để lấy
   toàn bộ tài liệu quý được adapter hỗ trợ. Trả `ingestionRunId`, `rawPayloadId`,
   `upstreamStatus`, `validatedDocuments`, `insertedDocuments`, `trainingEligible`,
   `vendorStatementsModified`. Nhấn refresh lại vẫn có run/raw audit, nhưng tài liệu
   không thay đổi sẽ có `insertedDocuments=0`. Không gọi liên tục bằng auto-refresh FE.

Chỉ FPT có adapter nguồn công bố chính thức trong đợt này. Mã khác ở thao tác refresh
bị từ chối 400 ở Java, 422 ở Python. Lỗi nguồn được giữ trong raw/run; contract sai
không được ghi vào bảng tài liệu. Java dùng validator riêng cho document, không
đưa document vào catalog build của FINANCIAL_STATEMENT.

FE nên có bảng tài liệu và cảnh báo dữ liệu chưa đủ bằng chứng. `UNKNOWN` là chưa
biết, không hiển thị thành “bản gốc”. `report_version_id` chỉ nhận diện bản ghi nguồn;
`revision_status` chỉ RESTATED khi nguồn ghi rõ điều chỉnh, còn lại UNKNOWN.
Ngày chỉ có độ chính xác DAY dùng cuối ngày Asia/Ho_Chi_Minh khi xét khả dụng.

## Ý nghĩa bảng và giới hạn

`financial_disclosure_documents` lưu bằng chứng tài liệu, không chứa dataset train
và không phải log gọi model PKL. `raw_payloads`/`ingestion_runs` giữ lịch sử lấy nguồn.
`financial_statements`/`financial_statement_items` vẫn là số vendor/canonical riêng.
Nguồn chính thức mới không tự chứng nhận số vendor đúng scope, đơn vị hoặc phiên bản.

FPT hiện có 156 báo cáo canonical còn thiếu ngày, scope và revision đã được xác minh;
1 báo cáo còn thiếu đơn vị. Việc có 88 tài liệu không đồng nghĩa tự điền được 156
báo cáo này. Cần liên kết từng số với đúng tài liệu, kỳ, scope và phiên bản trước
khi dùng cho train point-in-time. VNStock còn chênh số so với PDF chính thức và
chưa đủ metadata đơn vị/ngày ở output chuẩn hóa; chưa dùng thay nguồn đã xác minh.

Mô hình local AI chưa được train lại hay chuyển production trong đợt này. FE có thể
tích hợp các API trên; chưa được trình bày dữ liệu/model là đã đạt production.

## Chạy và kiểm lại

Áp `src/main/resources/db/manual/V20261024_01__financial_metadata_documents.sql`
trước chạy Java mới. Migration chỉ bổ sung schema, không backfill bằng suy đoán.
`scripts/FinancialMetadataMigration.java` cung cấp chế độ isolate/apply và yêu cầu
receipt PASS đúng SHA trước apply. Cần cấu hình DB riêng tư của môi trường đích.
Các helper `FinancialMetadataRecall`, `FinancialDisclosureIngestion`,
`FinancialMetadataHttpSmoke` gọi đúng service thật và tắt các scheduler.

Bằng chứng cục bộ: `artifacts/financial-provider-repair-20261024/` gồm `vercel-final`,
`metadata-vercel`, `http-vercel`, `fpt-live`, `fpt-pdfs`, `pdf-review`, `fpt_recheck.csv`
và `evidence/`. Không commit PDF, dump DB, khóa hoặc log riêng tư vào repo.
