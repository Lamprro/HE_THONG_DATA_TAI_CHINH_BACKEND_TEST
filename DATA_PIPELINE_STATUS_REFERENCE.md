# System Status and State Reference

## Mục đích

Tài liệu này là nguồn tham chiếu chung cho Dev và BA về mọi cột trạng thái, trạng thái chất
lượng, trạng thái nghiệp vụ và cờ trạng thái đang có trong các entity/bảng của hệ thống.

Nguồn đối chiếu:

- Entity, enum và service Java: xác định trạng thái mà backend hiện ghi/đọc.
- Báo cáo thiết kế hệ thống: xác định tập giá trị `CHECK` đã được thiết kế cho các module chưa có
  service.
- Database đang tạm dừng tại thời điểm cập nhật tài liệu; không có kiểm tra DB mới trong lần này.
  Khi DB hoạt động lại, cần đối chiếu lại các `CHECK` constraint trước khi dùng tài liệu làm hợp
  đồng API chính thức.

Luồng quan trọng nhất là:

```text
ingestion_run -> raw_payload -> validation_result -> data_version
```

Các trạng thái ở các lớp khác nhau không được suy diễn thay thế cho nhau. Ví dụ,
`ingestion_run = SUCCESS` chỉ xác nhận lấy và lưu raw thành công; nó không có nghĩa dữ liệu đã
được validation hoặc đã có `data_version`.

## Nguyên tắc cốt lõi

- `raw_payloads` là dữ liệu thô, immutable, có thể tồn tại dù validation fail.
- `validation_results` lưu kết quả kiểm tra của từng rule trên từng raw payload.
- Một `data_version` đại diện cho dữ liệu sạch được chấp nhận của **một ingestion run**, không
  phải của một raw payload riêng lẻ.
- Chỉ tạo `data_version` khi ingestion run đã `SUCCESS`, mọi raw payload của run đã được
  validation, và không có validation failure chặn run.

## 1. Ingestion run status

Trường: `ingestion_runs.status`  
Nguồn định nghĩa: `IngestionRunStatus`.

| Status | Ý nghĩa | Có thể validation? | Có thể tạo data version? |
| --- | --- | --- | --- |
| `PENDING` | Run đã được tạo nhưng chưa bắt đầu fetch. | Không | Không |
| `RUNNING` | Đang gọi nguồn ngoài hoặc đang lưu kết quả fetch. | Không nên | Không |
| `SUCCESS` | Fetch thành công và raw payload của run đã được lưu. | Có | Có, nếu đạt toàn bộ điều kiện validation |
| `PARTIAL_SUCCESS` | Chỉ một phần dữ liệu của run được fetch/lưu thành công. Enum đã có để hỗ trợ mở rộng; luồng hiện tại chưa ghi trạng thái này. | Cần policy riêng trước khi dùng | Không |
| `FAILED` | Fetch hoặc persist thất bại; chi tiết ở `error_message`, HTTP response và metadata. | Không | Không |
| `CANCELLED` | Run bị hủy. Enum đã có để hỗ trợ mở rộng; luồng hiện tại chưa ghi trạng thái này. | Không | Không |

Luồng đang được code ghi nhận trực tiếp là:

```text
RUNNING -> SUCCESS
RUNNING -> FAILED
```

## 2. Validation result status

Trường: `validation_results.result_status`  
Phạm vi: một rule trên một raw payload.

| Status | Ý nghĩa | Ảnh hưởng đến data version |
| --- | --- | --- |
| `PASS` | Rule đã chạy và raw payload đạt yêu cầu. | Không chặn |
| `FAIL` | Rule đã chạy và raw payload không đạt yêu cầu. | Chặn nếu severity là `ERROR` hoặc `CRITICAL`; xem policy duplicate bên dưới |
| `SKIP` | Rule không thể hoặc không cần áp dụng cho payload đó, ví dụ payload không có trường cần kiểm tra. | Không chặn |

`PASS` không có nghĩa toàn bộ ingestion run đã được chấp nhận. Nó chỉ mô tả một rule của một raw
payload. Quyết định chấp nhận chỉ xảy ra ở bước finalization của cả run.

## 3. Validation severity và policy chặn run

Trường: `validation_results.severity`, lấy từ `validation_rules.severity`.

| Severity | Ý nghĩa | Policy hiện tại |
| --- | --- | --- |
| `WARNING` | Cảnh báo cần quan sát nhưng thường không làm dữ liệu mất hiệu lực. | Không chặn data version |
| `ERROR` | Lỗi chất lượng dữ liệu. | `FAIL` chặn data version của cả run |
| `CRITICAL` | Lỗi nghiêm trọng, dữ liệu không đáng tin cậy. | `FAIL` chặn data version của cả run |

Ngoại lệ policy hiện tại: `NEWS_DUPLICATE_HASH` có severity `WARNING` nhưng nếu `FAIL` vẫn chặn
tạo data version để bảo vệ deduplication của dữ liệu news.

## 4. Validation handling status

Trường: `validation_results.handling_status`  
Phạm vi: vòng đời xử lý nghiệp vụ của một validation result, tách biệt với `result_status`.

| Status | Ý nghĩa | Khi nào dùng |
| --- | --- | --- |
| `NOT_REQUIRED` | Không cần xử lý thủ công. | Kết quả `PASS` hoặc `SKIP` |
| `OPEN` | Có failure cần được theo dõi hoặc xử lý. | Kết quả `FAIL` mới tạo |
| `RESOLVED` | Người có thẩm quyền đã xử lý và ghi nhận cách giải quyết. | Cập nhật `resolved_by_user_id`, `resolved_at`, `resolution_note` |
| `IGNORED` | Failure được chấp nhận có chủ đích và không xử lý tiếp. | Cần ghi lý do vào `resolution_note` |

Luồng hiện tại tự ghi `NOT_REQUIRED` cho `PASS`/`SKIP` và `OPEN` cho `FAIL`. API nghiệp vụ để
chuyển `OPEN` sang `RESOLVED` hoặc `IGNORED` là phần cần triển khai tiếp theo.

## 5. Validation execution response status

Trường: `ValidationExecutionResponse.status`  
Phạm vi: phản hồi của API/job khi vừa xử lý một raw payload. Đây không phải cột
`validation_results.result_status`.

| Status | Ý nghĩa chính xác |
| --- | --- |
| `VALIDATED` | Raw payload này đã được validate, nhưng ingestion run chưa tạo được data version. Có thể do các raw khác chưa validate, ingestion run chưa `SUCCESS`, hoặc run có failure chặn. |
| `ACCEPTED` | Finalization đã chấp nhận cả ingestion run và đã có `dataVersionId`. |
| `REJECTED` | Raw payload đang xử lý có `FAIL` mức `ERROR` hoặc `CRITICAL`. Không tạo data version cho run. |
| `DUPLICATE` | Raw payload đang xử lý fail rule `NEWS_DUPLICATE_HASH`. Không tạo data version cho run. |

`alreadyProcessed = true` nghĩa là raw payload đã có validation result từ lần trước; service chỉ
trả lại kết quả tổng hợp hiện có, không chạy lại rule và không tạo bản ghi validation trùng.

## 6. Data version status

Trường: `data_versions.status`.

| Status | Ý nghĩa | Luồng hiện tại |
| --- | --- | --- |
| `ACTIVE` | Phiên bản dữ liệu sạch được chấp nhận và có thể làm nguồn cho bước normalize, read API, AI/ML hoặc reporting sau này. | Đây là trạng thái duy nhất hiện được ghi khi finalization thành công. |
| `SUPERSEDED` | Phiên bản hợp lệ đã được thay thế bởi version mới hơn nhưng vẫn giữ để truy vết. | Schema/thiết kế; chưa có service chuyển trạng thái. |
| `ARCHIVED` | Phiên bản được lưu lịch sử, không còn là bản được phục vụ mặc định. | Schema/thiết kế; chưa có service chuyển trạng thái. |

Không dùng sự tồn tại của `data_version` để kết luận một raw payload đơn lẻ đã pass rule. Nó chỉ
xác nhận toàn bộ ingestion run tương ứng đã vượt qua bước finalization tại thời điểm tạo version.

## 7. Decision table cho finalization

| Điều kiện | Kết quả |
| --- | --- |
| Run không phải `SUCCESS` | Chưa tạo data version |
| Run không có raw payload | Không tạo data version |
| Còn raw payload chưa có validation result | Chưa tạo data version |
| Có `FAIL` severity `ERROR` hoặc `CRITICAL` | Run bị chặn, không tạo data version |
| Có `FAIL` rule `NEWS_DUPLICATE_HASH` | Run bị chặn, không tạo data version |
| Các điều kiện trên đều không xảy ra | Tạo đúng một `data_version` trạng thái `ACTIVE` cho run |

## 8. Ví dụ với một run có 100 raw payload

1. Run `SUCCESS` và sinh 100 raw payload.
2. Sau khi validate 1 payload pass: response là `VALIDATED`; chưa có data version.
3. Sau khi validate 99 payload: vẫn chưa có data version nếu payload cuối chưa validation.
4. Payload thứ 100 hoàn tất, không có failure chặn: tạo một data version `ACTIVE`, có
   `row_count = 100`.
5. Nếu bất kỳ payload nào có `FAIL/ERROR`, `FAIL/CRITICAL`, hoặc `NEWS_DUPLICATE_HASH` fail:
   không tạo data version; failure vẫn nằm tại `validation_results` để theo dõi.

## 9. Tổng hợp các cột trạng thái theo bảng

Ký hiệu trong cột “Mức độ sử dụng”:

- **Đang dùng:** backend hiện có code ghi hoặc dựa vào trạng thái đó.
- **Schema/thiết kế:** entity hoặc báo cáo đã định nghĩa nhưng chưa có service ghi trạng thái.
- **Cờ mô tả:** boolean mô tả thuộc tính hiện tại của record, không phải workflow transition.

| Bảng | Cột trạng thái hoặc cờ trạng thái | Giá trị / ý nghĩa | Mức độ sử dụng |
| --- | --- | --- | --- |
| `accounts` | `status` | `ACTIVE`, `INACTIVE`, `LOCKED`, `PENDING` | Schema/thiết kế |
| `companies` | `listing_status` | `LISTED`, `UNLISTED`, `DELISTED`, `SUSPENDED` | Đang dùng qua CRUD master |
| `companies` | `is_active` | `true`: company được hệ thống sử dụng; `false`: vẫn giữ lịch sử nhưng không hoạt động | Cờ mô tả, đang dùng |
| `securities` | `is_active` | `true`: mã còn được theo dõi và có thể provision job; `false`: ngừng theo dõi | Cờ mô tả, đang dùng |
| `securities` | `is_primary` | Đánh dấu mã chứng khoán chính của company | Cờ mô tả |
| `data_sources` | `license_status` | `UNKNOWN`, `FREE`, `LICENSED`, `RESTRICTED`, `INTERNAL` | Đang dùng và được validate ở API |
| `data_sources` | `is_active` | Bật/tắt nguồn cho ingestion | Cờ mô tả, đang dùng |
| `data_sources` | `is_official` | Đánh dấu nguồn chính thức, không đồng nghĩa quyền sử dụng | Cờ mô tả |
| `ingestion_jobs` | `is_active` | Bật/tắt job; `false` thì scheduler không chạy job đó | Cờ mô tả, đang dùng |
| `ingestion_runs` | `status` | `PENDING`, `RUNNING`, `SUCCESS`, `PARTIAL_SUCCESS`, `FAILED`, `CANCELLED` | Đang dùng; chi tiết tại phần 1 |
| `validation_rules` | `is_active` | Chỉ rule `true` được `ValidationJobService` thực thi | Cờ mô tả, đang dùng |
| `validation_results` | `result_status` | `PASS`, `FAIL`, `SKIP` | Đang dùng; chi tiết tại phần 2 |
| `validation_results` | `handling_status` | `NOT_REQUIRED`, `OPEN`, `RESOLVED`, `IGNORED` | Đang dùng một phần; chi tiết tại phần 4 |
| `data_versions` | `status` | `ACTIVE`, `SUPERSEDED`, `ARCHIVED` | `ACTIVE` đang được ghi; lifecycle còn lại là schema/thiết kế |
| `financial_metrics` | `quality_status` | `VALID`, `WARNING`, `REJECTED` | Schema/thiết kế |
| `financial_metrics` | `is_derived`, `is_canonical` | Derived: giá trị tính toán; Canonical: bản ghi được chọn làm bản chuẩn | Cờ mô tả |
| `financial_periods` | `is_audited_period` | Kỳ báo cáo đã được audit hay chưa | Cờ mô tả |
| `financial_statements` | `is_restated`, `is_current`, `is_canonical` | Bản điều chỉnh lại, bản hiện hành, và bản chuẩn tương ứng | Cờ mô tả |
| `financial_statement_items` | `is_total` | Dòng tổng cộng, không phải trạng thái xử lý | Cờ mô tả |
| `market_prices`, `index_prices` | `is_canonical` | Bản ghi giá được chọn làm canonical | Cờ mô tả |
| `market_indices`, `macro_series` | `is_active` | Bật/tắt index hoặc series cho sử dụng hệ thống | Cờ mô tả |
| `market_indices` | `is_benchmark` | Đánh dấu index dùng làm benchmark | Cờ mô tả |
| `news_articles` | `dedup_status` | `UNIQUE`, `DUPLICATE`, `POSSIBLE_DUPLICATE` | Schema/thiết kế |
| `news_articles` | `is_deleted_source` | Nguồn đã xóa bài; không xóa record nội bộ để giữ provenance | Cờ mô tả |
| `llm_runs` | `status` | `PENDING`, `RUNNING`, `SUCCESS`, `FAILED`, `REJECTED` | Schema/thiết kế |
| `news_ai_analyses` | `quality_status` | `VALID`, `WARNING`, `REJECTED` | Schema/thiết kế |
| `datasets` | `status` | `DRAFT`, `READY`, `FROZEN`, `ARCHIVED`, `REJECTED` | Schema/thiết kế |
| `model_versions` | `status` | `TRAINING`, `TRAINED`, `EVALUATING`, `CANDIDATE`, `ACTIVE`, `REJECTED`, `ARCHIVED` | Schema/thiết kế |
| `model_evaluations` | `acceptance_status` | `PASS`, `FAIL`, `REVIEW` | Schema/thiết kế |
| `predictions` | `predicted_label` | `OUTPERFORM`, `NOT_OUTPERFORM`, `REJECTED`, `UNKNOWN` | Schema/thiết kế; đây là kết quả dự báo, không phải workflow status |
| `prediction_outcomes` | `is_correct` | Ground truth xác nhận prediction đúng/sai | Cờ mô tả |
| `watchlists` | `is_default` | Watchlist mặc định của user | Cờ mô tả |
| `system_settings` | `is_secret` | Giá trị cấu hình nhạy cảm cần che khi trả API/log | Cờ mô tả |
| `metric_definitions` | `higher_is_better` | Quy ước đánh giá metric, không phải trạng thái | Cờ mô tả |

Các bảng entity không có cột status hoặc cờ trạng thái riêng hiện tại gồm: `analysis_reports`,
`audit_logs`, `data_lineage_events`, `dataset_samples`, `feature_sets`, `financial_statement_items`
(ngoài `is_total`), `macro_observations`, `news_article_companies`, `prediction_explanations`,
`roles`, `security_index_memberships`, `users`, `watchlist_items`, `raw_payloads` và các bảng khóa
ghép. Các bảng này được nhận biết bằng khóa, thời gian, liên kết provenance hoặc dữ liệu nghiệp vụ,
không bằng lifecycle status.

## 10. Định nghĩa chi tiết cho các trạng thái ngoài pipeline

### 10.1 Accounts

| Status | Ý nghĩa | Hành động nghiệp vụ dự kiến |
| --- | --- | --- |
| `PENDING` | Account đã được tạo nhưng chưa hoàn tất điều kiện kích hoạt, ví dụ xác minh email. | Không cho đăng nhập đầy đủ |
| `ACTIVE` | Account được phép sử dụng. | Cho phép xác thực theo role |
| `INACTIVE` | Account bị vô hiệu hóa thông thường nhưng không phải do sự cố bảo mật. | Không cho đăng nhập |
| `LOCKED` | Account bị khóa vì chính sách bảo mật hoặc quản trị. | Không cho đăng nhập; cần mở khóa có audit |

Hiện chưa có service authentication/authorization, vì vậy các transition account trên là thiết kế
schema, chưa phải behavior đã triển khai.

### 10.2 Company listing status

| Status | Ý nghĩa |
| --- | --- |
| `LISTED` | Company hiện có chứng khoán niêm yết/giao dịch theo dữ liệu master. |
| `UNLISTED` | Không niêm yết hoặc chưa có thông tin niêm yết. |
| `DELISTED` | Đã hủy niêm yết; vẫn phải giữ lịch sử dữ liệu. |
| `SUSPENDED` | Niêm yết/giao dịch bị tạm ngừng; không đồng nghĩa company bị xóa. |

`listing_status` khác `companies.is_active`: company có thể `DELISTED` nhưng vẫn `is_active=true`
để phục vụ đọc lịch sử.

### 10.3 Data source licensing status

| Status | Ý nghĩa |
| --- | --- |
| `UNKNOWN` | Chưa xác định được quyền sử dụng. Đây là default, không phải xác nhận được phép khai thác. |
| `FREE` | Có thể dùng miễn phí theo điều kiện đã ghi nhận. |
| `LICENSED` | Có hợp đồng hoặc giấy phép sử dụng. |
| `RESTRICTED` | Có hạn chế phạm vi, tần suất, mục đích hoặc phân phối. |
| `INTERNAL` | Nguồn dữ liệu nội bộ. |

`license_status` khác `data_sources.is_active`: nguồn có thể active về kỹ thuật nhưng bị
`RESTRICTED` về pháp lý; BA/Operator phải kiểm tra cả hai.

### 10.4 Canonical, current, restated và quality

| Trường | `true` / giá trị | `false` / giá trị còn lại |
| --- | --- | --- |
| `is_canonical` | Bản ghi được chọn làm chuẩn để downstream ưu tiên đọc. | Bản ghi vẫn lưu để truy vết/revision nhưng không là bản chuẩn. |
| `is_current` | Bản financial statement hiện hành trong cùng phạm vi nghiệp vụ. | Bản cũ hoặc đã được thay thế. |
| `is_restated` | Bản được điều chỉnh lại so với báo cáo trước đó. | Không có dấu hiệu restatement. |
| `quality_status = VALID` | Dữ liệu đạt chất lượng cho mục đích dùng tiếp. | — |
| `quality_status = WARNING` | Có bất thường không chặn nhưng cần quan sát. | — |
| `quality_status = REJECTED` | Dữ liệu không được chấp nhận cho canonical/downstream. | — |

`quality_status` thuộc lớp clean/normalized data trong các module chưa triển khai. Không được thay
cho `validation_results.result_status`: validation result là bằng chứng theo rule trên raw payload,
còn quality status là kết luận chất lượng của dữ liệu đã được map/normalize.

### 10.5 News deduplication status

| Status | Ý nghĩa |
| --- | --- |
| `UNIQUE` | Bài viết không trùng theo policy dedup. |
| `POSSIBLE_DUPLICATE` | Có tín hiệu trùng nhưng chưa đủ bằng chứng để gộp. |
| `DUPLICATE` | Bài viết trùng bản canonical khác; dùng `duplicate_of_news_article_id` để truy vết. |

Đây là status của `news_articles` sau normalize/dedup, khác với `NEWS_DUPLICATE_HASH` trong
validation raw. Rule raw chặn tạo data version theo policy hiện tại; dedup status quyết định quan
hệ giữa các bài canonical khi News module được triển khai.

### 10.6 LLM, dataset, model và evaluation

| Bảng / cột | Các trạng thái | Ý nghĩa lifecycle |
| --- | --- | --- |
| `llm_runs.status` | `PENDING` -> `RUNNING` -> `SUCCESS` / `FAILED` / `REJECTED` | Theo dõi một request LLM. `REJECTED` dùng khi backend chủ động từ chối vì thiếu điều kiện/context. |
| `datasets.status` | `DRAFT` -> `READY` -> `FROZEN` -> `ARCHIVED`; có thể `REJECTED` | `FROZEN` là dataset không được thay đổi, có thể tái lập train/evaluate. |
| `model_versions.status` | `TRAINING` -> `TRAINED` -> `EVALUATING` -> `CANDIDATE` -> `ACTIVE`; hoặc `REJECTED` / `ARCHIVED` | Chỉ `ACTIVE` được dùng cho inference production. |
| `model_evaluations.acceptance_status` | `REVIEW`, `PASS`, `FAIL` | Kết luận đánh giá một model trên dataset/split; không tự động đồng nghĩa model đã `ACTIVE`. |

Các lifecycle ở phần này mới là schema/thiết kế; hiện không có service chuyển trạng thái.

## 11. Quy tắc không được nhầm trạng thái

| Không được nhầm | Phân biệt đúng |
| --- | --- |
| `ingestion_runs.SUCCESS` và `data_versions.ACTIVE` | `SUCCESS` = lấy raw thành công; `ACTIVE` = cả run đã qua finalization validation. |
| `validation_results.FAIL` và `handling_status.OPEN` | `FAIL` = kết quả kỹ thuật của rule; `OPEN` = còn cần xử lý nghiệp vụ. |
| `validation_results.SKIP` và `FAIL` | `SKIP` không phải lỗi, không chặn run theo policy hiện tại. |
| `quality_status.REJECTED` và `data_versions` không tồn tại | `REJECTED` là quality của dữ liệu normalized trong module sau; không có data version nghĩa run raw chưa được chấp nhận. |
| `NEWS_DUPLICATE_HASH` fail và `news_articles.dedup_status` | Cái đầu là rule raw-level; cái sau là kết quả dedup canonical-level. |
| `is_active=false` và xóa dữ liệu | `is_active` chỉ vô hiệu hóa sử dụng; lịch sử/provenance vẫn được giữ. |

## 12. Quy ước khi bổ sung status mới

1. Không thêm giá trị string trực tiếp trong service trước khi cập nhật enum hoặc constraint schema.
2. Mỗi status phải có: owner chuyển trạng thái, điều kiện vào/ra, tác động đến downstream và log/audit.
3. Nếu là kết quả kiểm tra, dùng `result_status`; nếu là tiến độ xử lý thủ công, dùng
   `handling_status`; không gộp hai ý nghĩa vào một cột.
4. Mọi thay đổi tập giá trị phải cập nhật tài liệu này, API contract và migration/constraint DB cùng
   một pull request.
