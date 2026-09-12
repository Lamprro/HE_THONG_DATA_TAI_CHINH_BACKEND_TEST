# System Status Reference

Tài liệu này dùng để tra nhanh: trạng thái nằm ở **bảng nào**, **thuộc tính nào** và ý nghĩa của nó.

- `status`, `*_status`: tiến độ hoặc kết quả.
- `is_*`: cờ đúng/sai của record.

Trạng thái “Đang dùng” lấy từ code/DB đã đối chiếu; “Thiết kế” là trạng thái có trong schema hoặc tài liệu nhưng chưa có xử lý đầy đủ.

## 1. Luồng dữ liệu

| Bảng | Thuộc tính | Giá trị | Ý nghĩa | Nguồn |
| --- | --- | --- | --- | --- |
| `ingestion_runs` | `status` | `PENDING` | Đã tạo run, chưa chạy. | Thiết kế |
| `ingestion_runs` | `status` | `RUNNING` | Đang lấy dữ liệu. | Đang dùng |
| `ingestion_runs` | `status` | `SUCCESS` | Đã lưu raw payload thành công. | Đang dùng |
| `ingestion_runs` | `status` | `PARTIAL_SUCCESS` | Chỉ lấy được một phần dữ liệu. | Thiết kế |
| `ingestion_runs` | `status` | `FAILED` | Lấy hoặc lưu dữ liệu lỗi. | Đang dùng |
| `ingestion_runs` | `status` | `CANCELLED` | Run bị hủy. | Thiết kế |
| `validation_results` | `result_status` | `PASS` | Rule kiểm tra đạt. | Đang dùng |
| `validation_results` | `result_status` | `FAIL` | Rule kiểm tra không đạt. | Đang dùng |
| `validation_results` | `result_status` | `SKIP` | Rule không áp dụng. | Đang dùng |
| `validation_results` | `handling_status` | `NOT_REQUIRED` | Không cần xử lý. Dùng cho `PASS`/`SKIP`. | Đang dùng |
| `validation_results` | `handling_status` | `OPEN` | Có lỗi cần theo dõi. Dùng cho `FAIL`. | Đang dùng |
| `validation_results` | `handling_status` | `RESOLVED` | Lỗi đã xử lý. | Thiết kế một phần |
| `validation_results` | `handling_status` | `IGNORED` | Đồng ý bỏ qua lỗi. | Thiết kế một phần |
| `data_versions` | `status` | `ACTIVE` | Dữ liệu sạch của cả run đã được chấp nhận. | Đang dùng |
| `data_versions` | `status` | `ACTIVATE` | Tên trạng thái nghiệp vụ thống nhất cho version NEWS/NEWS_DATA đang chờ job kế tiếp. | Quy ước mới; cần đồng bộ code |
| `data_versions` | `status` | `ACTIVATED` | Version NEWS/NEWS_DATA đã được job kế tiếp tiêu thụ thành công. | Đang dùng trong DB |
| `data_versions` | `status` | `SUPERSEDED` | Version cũ đã có version mới thay thế. | Thiết kế |
| `data_versions` | `status` | `ARCHIVED` | Version chỉ để lưu lịch sử. | Thiết kế |

Tạo một record `data_versions` khi run là `SUCCESS`, tất cả `raw_payloads` của run đã validate,
không có `FAIL` mức `ERROR`/`CRITICAL`, và không fail `NEWS_DUPLICATE_HASH`.

## 2. Mức độ lỗi validation

| Bảng | Thuộc tính | Giá trị | Ý nghĩa |
| --- | --- | --- | --- |
| `validation_rules`, `validation_results` | `severity` | `WARNING` | Cảnh báo; thường không chặn tạo version. |
| `validation_rules`, `validation_results` | `severity` | `ERROR` | Nếu `FAIL`, chặn tạo version. |
| `validation_rules`, `validation_results` | `severity` | `CRITICAL` | Nếu `FAIL`, chặn tạo version. |

## 3. Tài khoản, công ty và nguồn dữ liệu

| Bảng | Thuộc tính | Giá trị | Ý nghĩa | Nguồn |
| --- | --- | --- | --- | --- |
| `accounts` | `status` | `PENDING` | Chưa kích hoạt xong. | Thiết kế |
| `accounts` | `status` | `ACTIVE` | Được phép dùng. | Thiết kế |
| `accounts` | `status` | `INACTIVE` | Tạm vô hiệu hóa. | Thiết kế |
| `accounts` | `status` | `LOCKED` | Bị khóa. | Thiết kế |
| `companies` | `listing_status` | `LISTED` | Đang niêm yết. | Đang dùng |
| `companies` | `listing_status` | `UNLISTED` | Không niêm yết hoặc chưa rõ. | Đang dùng |
| `companies` | `listing_status` | `DELISTED` | Đã hủy niêm yết. | Đang dùng |
| `companies` | `listing_status` | `SUSPENDED` | Tạm ngừng giao dịch/niêm yết. | Đang dùng |
| `data_sources` | `license_status` | `UNKNOWN` | Chưa rõ quyền sử dụng. | Đang dùng |
| `data_sources` | `license_status` | `FREE` | Dùng miễn phí theo điều kiện nguồn. | Đang dùng |
| `data_sources` | `license_status` | `LICENSED` | Có giấy phép hoặc hợp đồng. | Đang dùng |
| `data_sources` | `license_status` | `RESTRICTED` | Có giới hạn sử dụng. | Đang dùng |
| `data_sources` | `license_status` | `INTERNAL` | Nguồn nội bộ. | Đang dùng |

## 4. Chất lượng dữ liệu và tin tức

| Bảng | Thuộc tính | Giá trị | Ý nghĩa | Nguồn |
| --- | --- | --- | --- | --- |
| `financial_metrics` | `quality_status` | `VALID` | Dữ liệu đạt chất lượng. | Thiết kế |
| `financial_metrics` | `quality_status` | `WARNING` | Có cảnh báo. | Thiết kế |
| `financial_metrics` | `quality_status` | `REJECTED` | Không dùng làm dữ liệu chuẩn. | Thiết kế |
| `news_ai_analyses` | `quality_status` | `VALID` | Kết quả AI đạt yêu cầu. | Thiết kế |
| `news_ai_analyses` | `quality_status` | `WARNING` | Kết quả AI cần xem lại. | Thiết kế |
| `news_ai_analyses` | `quality_status` | `REJECTED` | Không dùng kết quả AI. | Thiết kế |
| `news_articles` | `dedup_status` | `UNIQUE` | Bài viết không trùng. | Thiết kế |
| `news_articles` | `dedup_status` | `POSSIBLE_DUPLICATE` | Có thể trùng. | Thiết kế |
| `news_articles` | `dedup_status` | `DUPLICATE` | Bài viết trùng bài khác. | Thiết kế |

## 5. AI và model

| Bảng | Thuộc tính | Giá trị | Ý nghĩa | Nguồn |
| --- | --- | --- | --- | --- |
| `llm_runs` | `status` | `PENDING`, `RUNNING` | Chờ gọi / đang gọi LLM. | Thiết kế |
| `llm_runs` | `status` | `SUCCESS`, `FAILED`, `REJECTED` | Gọi xong / lỗi / không được gọi. | Thiết kế |
| `datasets` | `status` | `DRAFT`, `READY` | Đang chuẩn bị / sẵn sàng. | Thiết kế |
| `datasets` | `status` | `FROZEN`, `ARCHIVED`, `REJECTED` | Đã chốt / lưu lịch sử / không đạt. | Thiết kế |
| `model_versions` | `status` | `TRAINING`, `TRAINED`, `EVALUATING` | Đang train / train xong / đang đánh giá. | Thiết kế |
| `model_versions` | `status` | `CANDIDATE`, `ACTIVE` | Chờ chọn / đang dùng. | Thiết kế |
| `model_versions` | `status` | `REJECTED`, `ARCHIVED` | Không đạt / lưu lịch sử. | Thiết kế |
| `model_evaluations` | `acceptance_status` | `REVIEW`, `PASS`, `FAIL` | Cần xem / đạt / không đạt. | Thiết kế |
| `predictions` | `predicted_label` | `OUTPERFORM` | Dự báo tốt hơn benchmark. | Thiết kế |
| `predictions` | `predicted_label` | `NOT_OUTPERFORM` | Dự báo không tốt hơn benchmark. | Thiết kế |
| `predictions` | `predicted_label` | `REJECTED`, `UNKNOWN` | Không đủ điều kiện / chưa rõ kết quả. | Thiết kế |

## 6. Các cờ đúng/sai

| Bảng | Thuộc tính | `true` | `false` |
| --- | --- | --- | --- |
| `companies` | `is_active` | Đang dùng. | Không dùng cho hoạt động mới. |
| `securities` | `is_active` | Còn theo dõi mã. | Ngừng theo dõi mã. |
| `securities` | `is_primary` | Mã chính của công ty. | Mã phụ. |
| `data_sources` | `is_active` | Cho ingestion dùng nguồn. | Không dùng nguồn để ingestion. |
| `data_sources` | `is_official` | Nguồn chính thức. | Nguồn không chính thức. |
| `ingestion_jobs` | `is_active` | Scheduler có thể chạy job. | Scheduler không chạy job. |
| `validation_rules` | `is_active` | Rule được chạy. | Rule không chạy. |
| `market_indices` | `is_active` | Index đang dùng. | Index không dùng. |
| `market_indices` | `is_benchmark` | Dùng làm benchmark. | Không làm benchmark. |
| `macro_series` | `is_active` | Series đang dùng. | Series không dùng. |
| `financial_metrics` | `is_derived` | Giá trị do hệ thống tính. | Giá trị từ nguồn. |
| `financial_metrics` | `is_canonical` | Bản ghi chuẩn. | Bản ghi phụ/lịch sử. |
| `financial_periods` | `is_audited_period` | Kỳ đã audit. | Kỳ chưa audit. |
| `financial_statements` | `is_restated` | Báo cáo đã điều chỉnh. | Không điều chỉnh. |
| `financial_statements` | `is_current` | Báo cáo hiện hành. | Báo cáo cũ. |
| `financial_statements` | `is_canonical` | Báo cáo chuẩn. | Báo cáo phụ. |
| `financial_statement_items` | `is_total` | Dòng tổng. | Dòng chi tiết. |
| `market_prices`, `index_prices` | `is_canonical` | Giá chuẩn. | Giá phụ/lịch sử. |
| `news_articles` | `is_deleted_source` | Nguồn đã xóa bài. | Nguồn chưa xóa bài. |
| `prediction_outcomes` | `is_correct` | Dự báo đúng. | Dự báo sai. |
| `watchlists` | `is_default` | Watchlist mặc định. | Watchlist thường. |
| `system_settings` | `is_secret` | Che trong log/API. | Có thể trả bình thường. |
| `metric_definitions` | `higher_is_better` | Cao hơn là tốt hơn. | Thấp hơn là tốt hơn. |

## 7. Response của API validation

`ValidationExecutionResponse.status` không nằm trong bảng DB.

| Giá trị | Ý nghĩa |
| --- | --- |
| `VALIDATED` | Raw payload đã validate, nhưng cả run chưa có data version. |
| `ACCEPTED` | Cả ingestion run đạt điều kiện và đã có data version. |
| `REJECTED` | Raw payload có lỗi `ERROR` hoặc `CRITICAL`. |
| `DUPLICATE` | Raw payload fail rule duplicate news. |

## 8. Bảng không có thuộc tính trạng thái riêng

`analysis_reports`, `audit_logs`, `data_lineage_events`, `dataset_samples`, `feature_sets`,
`macro_observations`, `news_article_companies`, `prediction_explanations`, `roles`,
`security_index_memberships`, `users`, `watchlist_items` và `raw_payloads`.

## 9. Trạng thái và dữ liệu riêng của NEWS

| Bảng | Thuộc tính | Giá trị/đường dẫn | Ý nghĩa |
| --- | --- | --- | --- |
| `raw_payloads` | `entity_type` | `NEWS` | Response danh sách tin; link bài nằm trong `payload.data[].url`. |
| `raw_payloads` | `entity_type` | `NEWS_DATA` | Response trang báo; metadata nằm trong `payload`, HTML đầy đủ nằm trong `raw_text`. |
| `raw_payloads` | `content_type` | `application/json` | Thường dùng cho NEWS list. |
| `raw_payloads` | `content_type` | `text/html; charset=utf-8` | Thường dùng cho NEWS_DATA. |
| `ingestion_jobs` | `code` | `NEWS_DATA_FETCH` | Lấy URL bài báo từ version NEWS và gọi Python. |
| `ingestion_jobs` | `code` | `NEWS_ARTICLE_BUILD` | Đọc version NEWS_DATA đã validate và ghi bảng tin. |
| `news_articles` | `dedup_status` | `UNIQUE`, `POSSIBLE_DUPLICATE`, `DUPLICATE` | Kết quả chống trùng ở cấp bài báo; khác với checksum của raw response. |

Trong code hiện tại, `ValidationJobService` query trạng thái chờ là `ACTIVE`, còn nghiệp vụ NEWS
đã thống nhất tên `ACTIVATE`. Hai tên này không được dùng lẫn trong code và DB; cần một lần đồng bộ
riêng trước khi đổi scheduler sang `ACTIVATE`.
