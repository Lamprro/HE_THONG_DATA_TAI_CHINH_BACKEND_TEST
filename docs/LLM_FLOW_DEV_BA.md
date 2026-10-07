# Luồng xử lý LLM cho tin tức và tài chính

Tài liệu bàn giao cho lập trình viên và chuyên viên phân tích nghiệp vụ. Ngày đối chiếu 04/10/2026, nhánh master, HEAD 918a253 và các thay đổi local chưa commit. Phạm vi gồm luồng NEWS có nội dung, luồng kịch bản tài chính, các bảng lưu vết và các API vận hành. Bản Word được dựng từ nội dung này.

Hệ thống đã thực hiện được việc gửi dữ liệu sang Gemini, lưu response, kiểm tra theo luật trong DB và công bố kết quả JSON liên kết nguồn. Tuy nhiên, nghiệm thu kỹ thuật thành công chưa đủ để công bố các con số là dự báo tài chính đã kiểm định. Các điểm chặn production được trình bày riêng trong PRODUCTION_DATA_FLOW_REVIEW.md.

## 1 Cách đọc và thuật ngữ

BA nên đọc phần 2 đến 6 để xác định ý nghĩa đầu vào, đầu ra và trạng thái. Dev đọc thêm phần 7 đến 11 để lần theo phương thức, transaction, API và quy trình xử lý lỗi. Các liên kết lớp và phương thức bên dưới trỏ đến mã local tại thời điểm lập báo cáo; vị trí dòng có thể đổi sau khi sửa code.

| Thuật ngữ | Ý nghĩa trong hệ thống |
|---|---|
| raw_payloads | Response nguồn ngoài đã thu thập, phục vụ kiểm tra và truy vết |
| ingestion_runs | Một lần thực hiện lấy hoặc dựng dữ liệu nguồn |
| data_versions | Đơn vị phê duyệt theo ingestion run; ACTIVE đang chờ dựng, ACTIVATED đã được xử lý, REJECTED bị loại |
| canonical | Bản được chính sách chọn làm nguồn ưu tiên giữa các nhà cung cấp; không tự chứng minh độ chính xác kinh tế |
| llm_runs | Một tác vụ AI có input snapshot, request, response, trạng thái và template |
| llm_run_attempts | Từng lần gọi model trong cùng một run, gồm fallback, HTTP, token và độ trễ |
| validation_results | Kết quả từng luật theo vòng kiểm tra; AI dùng validation_target LLM_OUTPUT và llm_run_id |
| llm_results | Kết quả AI được chấp nhận, JSON có version, liên kết nguồn và vòng validation |
| source_hash | Dấu nhận diện nội dung và thông tin nguồn dùng trong phân tích |
| input_hash | Dấu nhận diện request cùng template, danh sách model và policy; dùng chống gọi trùng |
| evidence | Đoạn trích nguyên văn trong NEWS hoặc ID điểm dữ liệu trong FINANCIAL |

Không đồng nhất ba trạng thái: HTTP 200 nghĩa API trả được response; llm_runs.SUCCESS nghĩa response vượt các kiểm tra được triển khai; result_json.status mô tả mức đủ dữ liệu do model khai báo. quality_status VALID hoặc WARNING là đánh giá hợp đồng dữ liệu, không phải tỷ lệ dự báo đúng.

## 2 NEWS được đưa vào LLM từ đâu

NEWS_DATA là dữ liệu trung gian sau khi lấy URL. Đầu vào trực tiếp của LLM là news_articles đã được dựng từ NEWS_DATA, cộng các quan hệ news_article_companies. LLM không tự tải lại URL và không nhận toàn bộ HTML trang báo.

Luồng trước AI: job NEWS gọi Python lấy danh sách → raw_payloads NEWS → validation → data_versions ACTIVE → NEWS_DATA_FETCH tải từng URL → raw_payloads NEWS_DATA → validation → NEWS_ARTICLE_BUILD → news_articles và news_article_companies. Chi tiết ở [NewsWorkflowService.fetchNewsData dòng 96](D:/HeThong_PhanTichTaiChinh/HE_THONG_DATA_TAI_CHINH_TEST/src/main/java/com/hethongdata/taichinh/service/news/NewsWorkflowService.java:96), [NewsWorkflowService.fetchOne dòng 209](D:/HeThong_PhanTichTaiChinh/HE_THONG_DATA_TAI_CHINH_TEST/src/main/java/com/hethongdata/taichinh/service/news/NewsWorkflowService.java:209), [NewsWorkflowService.articleDraft dòng 305](D:/HeThong_PhanTichTaiChinh/HE_THONG_DATA_TAI_CHINH_TEST/src/main/java/com/hethongdata/taichinh/service/news/NewsWorkflowService.java:305) và [NewsWorkflowPersistenceService.persistBuiltArticles dòng 87](D:/HeThong_PhanTichTaiChinh/HE_THONG_DATA_TAI_CHINH_TEST/src/main/java/com/hethongdata/taichinh/service/news/NewsWorkflowPersistenceService.java:87).

Ngày đăng ưu tiên ngày đọc từ bài, dự phòng ngày của đúng mục NEWS tương ứng URL. Ngày dạng dd/MM/yyyy HH:mm được hiểu theo Việt Nam rồi đổi sang Instant. crawled_at là thời điểm tải. extraction_version chỉ là metadata nếu nguồn có cung cấp; code dựng bài không bắt buộc trường này.

Một bài có thể liên quan nhiều công ty. Quan hệ RULE có bằng chứng source_job giữ nguồn thu thập theo yêu cầu nghiệp vụ, kể cả bài không nhắc công ty đó. TEXT_MATCH là nhận diện qua tiêu đề, sapo và thân bài. Có quan hệ nguồn không đồng nghĩa AI được phép kết luận công ty bị tác động; prompt yêu cầu bằng chứng từ nội dung.

URL trùng hoặc content_hash trùng sẽ tìm lại bài đã có. Nếu thêm được quan hệ nguồn mới hoặc cập nhật nội dung mới của cùng URL, version có thể ACTIVATED. Nếu toàn batch không thêm bài, không cập nhật nội dung, không thêm quan hệ thì REJECTED. Đây là chống trùng ở bảng bài báo; raw checksum là lớp kiểm tra riêng và không thay thế nó.

### 2.1 Điều kiện đủ để gửi NEWS

[NewsLlmContext.build dòng 22](D:/HeThong_PhanTichTaiChinh/HE_THONG_DATA_TAI_CHINH_TEST/src/main/java/com/hethongdata/taichinh/service/llm/NewsLlmContext.java:22) đọc bài, kiểm tra điều kiện và tạo snapshot. Những bài thiếu nội dung vẫn có thể hiển thị URL nhưng không đủ điều kiện AI.

| Kiểm tra | Hành vi hiện tại |
|---|---|
| Thân bài thiếu hoặc dưới 200 ký tự sau trim | BODY_MISSING_OR_TOO_SHORT, execute SKIPPED |
| Thân bài trên 150000 ký tự | BODY_EXCEEDS_CONTEXT_LIMIT, không tự cắt mất phần cuối |
| Thiếu title hoặc published_at | TITLE_MISSING hoặc PUBLISHED_AT_MISSING |
| dedup_status khác UNIQUE hoặc có duplicate_of | DUPLICATE_OR_UNRESOLVED |
| Nguồn đã xóa | DELETED_SOURCE |
| metadata is_test hoặc llm_excluded hoặc URL mẫu thử | TEST_OR_EXCLUDED_SOURCE |
| Có nguyên cụm menu CafeF đã biết | PAGE_CHROME_IN_BODY |
| SHA256 thân bài khác content_hash lưu | CONTENT_HASH_MISMATCH |

Bộ lọc menu này chỉ nhận diện dấu hiệu đã biết. Không được diễn giải việc vượt kiểm tra thành đã loại mọi quảng cáo, nội dung gây nhiễu hoặc mọi chỉ dẫn độc hại trong bài.

### 2.2 Hợp đồng đầu vào NEWS

[NewsLlmContext.segmentText dòng 68](D:/HeThong_PhanTichTaiChinh/HE_THONG_DATA_TAI_CHINH_TEST/src/main/java/com/hethongdata/taichinh/service/llm/NewsLlmContext.java:68) chia thân bài thành các đoạn khoảng tối đa 1800 ký tự, cố giữ ranh giới câu và nguyên văn liên tục. ID p1, p2 và các ID kế tiếp là vị trí bằng chứng để Java kiểm tra quote.

| Trường | Nội dung và mục đích |
|---|---|
| schema_version và task_code | news.input.v1 và tác vụ đang chạy |
| article.id | ID bài gốc, phải được phản hồi đúng trong source_article_id |
| article.title và sapo | Tiêu đề và lời dẫn; sapo thiếu được gửi chuỗi rỗng |
| article.published_at | Thời điểm đăng có offset +07:00 |
| article.url và content_hash | URL chuẩn hóa và dấu nhận diện thân bài |
| article.segments[] | id và text nguyên văn; nguồn duy nhất cho evidence.quote |
| article.companies[] | company_id, security_id, name, symbol, match_method, match_evidence |

Input được kiểm tra bằng request_schema của prompt trước gọi model. Nội dung bài được đặt trong request như dữ liệu; system_prompt yêu cầu không thực hiện chỉ dẫn trong bài, không tự mở URL hoặc thêm kiến thức bên ngoài.

## 3 NEWS trả ra những gì

Ba tác vụ độc lập về lưu trữ, có thứ tự nghiệp vụ SUMMARY → DETAIL → FINANCIAL_FACTS. Các version active hiện dùng SUMMARY v1, DETAIL v1 và FINANCIAL_FACTS v3.

| Tác vụ | Chức năng | Phần đầu ra riêng |
|---|---|---|
| NEWS_SUMMARY | Tóm tắt nội dung và các ý chính | overview, sections từ 1 đến 6 ý theo schema |
| NEWS_DETAIL | Phân loại và diễn giải tác động có căn cứ | document_types, contains_financial_figures, contains_forecasts, company_impacts |
| NEWS_FINANCIAL_FACTS | Trích số liệu, kế hoạch hoặc dự báo đã có trong bài | facts gồm chỉ tiêu, giá trị nguyên văn, đơn vị, kỳ, chủ thể phát biểu và loại fact |

Các trường chung: schema_version news.output.v1, task_code, source_article_id, status, overview, overview_evidence, sections, limitations. Mỗi section có type, heading, content và evidence. Không cho phép tùy ý thêm trường ngoài schema. FE cần đọc version/schema theo task, không coi mọi task có một bộ trường giống hệt nhau.

### 3.1 Bảng ý nghĩa đầu ra phân tích bài

| Trường | Ý nghĩa | Giới hạn khi sử dụng |
|---|---|---|
| overview | Tổng quan của tác vụ | Phải đọc cùng overview_evidence |
| sections[] | Danh sách phần nội dung có cấu trúc | Chỉ hiển thị các mục thực sự được trả về |
| evidence.segment_id và quote | Trích nguyên văn dài 8 đến 800 ký tự từ đoạn nguồn | Quote tồn tại chưa chứng minh kết luận suy diễn hoàn toàn đúng |
| document_types[] | CORPORATE_EVENT, FINANCIAL_RESULTS, MARKET_COMMENTARY, RESEARCH_OPINION, FORECAST, DISCLOSURE, OTHER | Có thể nhiều nhóm; chưa có đánh giá accuracy phân loại |
| contains_financial_figures | Model nhận định bài có số liệu tài chính | Dùng cùng contains_forecasts để mở tác vụ FACTS |
| contains_forecasts | Bài có đề cập dự báo | Không có nghĩa hệ thống tự dự báo |
| company_impacts.company_id | Công ty được phân tích tác động | ID phải thuộc danh sách công ty đầu vào |
| direction | POSITIVE, NEGATIVE, NEUTRAL, MIXED, UNKNOWN | Nhận định định tính có evidence, không phải lệnh mua bán |
| horizon | INTRADAY, SHORT_TERM, MEDIUM_TERM, LONG_TERM, UNKNOWN | Khoảng tác động định tính, chưa quy đổi thành số ngày thống nhất |
| confidence | Giá trị 0 đến 1 do model tự khai báo | Chưa hiệu chuẩn thành xác suất đúng |
| rationale | Lý do đánh giá tác động | Cần đi cùng evidence |

### 3.2 Bảng số liệu trích từ bài

| Trường trong facts | Ý nghĩa |
|---|---|
| metric_name | Tên chỉ tiêu theo ngữ cảnh bài |
| company_id | ID công ty đã biết; có thể null nếu không định danh được |
| value_text | Chuỗi số hoặc khoảng số nguyên văn; chưa phải số đã chuẩn hóa đơn vị |
| unit | Đơn vị nguyên văn trong evidence của chính fact; thiếu thì null |
| period | Kỳ thời gian nguyên văn trong evidence; thiếu thì null |
| attributed_to | Chủ thể phát biểu hoặc dự báo được bài dẫn; thiếu thì null |
| fact_kind ACTUAL | Bài trình bày là số liệu đã công bố |
| fact_kind FORECAST | Dự báo của nguồn được bài dẫn |
| fact_kind TARGET | Kế hoạch hoặc mục tiêu được bài dẫn |
| evidence[] | Bằng chứng riêng cho fact này, không dùng bằng chứng của fact khác để lấp trường |

FACTS không tính phần trăm, không đổi “tỷ đồng” thành VND, không điền giá trị thiếu. Nếu muốn đưa fact sang kho chỉ số tài chính, cần một bước xác minh nguồn, đơn vị, kỳ và mapping riêng. Hiện các fact vẫn lưu trong JSON kết quả NEWS.

Cần phân biệt hai nhánh NOT_APPLICABLE. Nếu DETAIL xác định không cần FACTS, service bỏ qua lời gọi và không tạo result mới. Nếu FACTS đã được gọi nhưng model trả NOT_APPLICABLE với facts rỗng và limitations hợp lệ, response có thể được lưu thành result WARNING sau validation.

## 4 NEWS đi qua các phương thức nào

### 4.1 Vào từ API hoặc scheduler

[NewsLlmAdminController.execute dòng 26](D:/HeThong_PhanTichTaiChinh/HE_THONG_DATA_TAI_CHINH_TEST/src/main/java/com/hethongdata/taichinh/controller/admin/NewsLlmAdminController.java:26) nhận articleId và gọi [NewsLlmService.executeAll dòng 117](D:/HeThong_PhanTichTaiChinh/HE_THONG_DATA_TAI_CHINH_TEST/src/main/java/com/hethongdata/taichinh/service/llm/NewsLlmService.java:117). executeAll duyệt các task trong LlmPromptCatalog.TASKS; task tắt trả TEMPLATE_DISABLED. API chạy một task gọi trực tiếp [NewsLlmService.execute dòng 46](D:/HeThong_PhanTichTaiChinh/HE_THONG_DATA_TAI_CHINH_TEST/src/main/java/com/hethongdata/taichinh/service/llm/NewsLlmService.java:46). Preview chỉ dựng input và request mẫu, không gọi sinh nội dung.

[NewsLlmScheduler.tick dòng 19](D:/HeThong_PhanTichTaiChinh/HE_THONG_DATA_TAI_CHINH_TEST/src/main/java/com/hethongdata/taichinh/scheduler/llm/NewsLlmScheduler.java:19) khi được bật sẽ validatePending trước, rồi chọn tối đa 5 bài từ candidates và gọi executeAll. candidates lọc bài có nội dung, run đang chạy, run chờ validation và bài vừa lỗi trong một giờ. Lịch mặc định mỗi 300000 ms sau lần trước; cấu hình hiện tắt scheduler. Forecast chưa có scheduler tự sinh.

### 4.2 Điều phối một tác vụ

Trong [NewsLlmService.execute dòng 46](D:/HeThong_PhanTichTaiChinh/HE_THONG_DATA_TAI_CHINH_TEST/src/main/java/com/hethongdata/taichinh/service/llm/NewsLlmService.java:46), catalog.active đọc prompt/schema active từ DB. contexts.build tạo input, kiểm tra đủ điều kiện; validator.schema kiểm tra request. Với FINANCIAL_FACTS, service phải tìm được NEWS_DETAIL hiện hành qua store.results. Thiếu DETAIL trả WAITING_FOR_DETAIL. DETAIL báo không có số liệu lẫn dự báo thì retireFinancial và trả NOT_APPLICABLE, không gọi model.

Service kiểm tra gateway.configured và validations.fingerprint. Thiếu khóa hoặc bị tắt trả CONFIGURATION_REQUIRED; bộ luật thiếu hoặc sai cấu hình trả VALIDATION_CONFIGURATION_ERROR. Dữ liệu và prompt đủ điều kiện mới dựng provider request và input_hash.

[LlmRunStore.claim dòng 26](D:/HeThong_PhanTichTaiChinh/HE_THONG_DATA_TAI_CHINH_TEST/src/main/java/com/hethongdata/taichinh/service/llm/LlmRunStore.java:26) lấy advisory lock theo article và task. Cùng input đã có result current trả CACHED. Có RUNNING hoặc PENDING_VALIDATION trả RUNNING. RUNNING quá 10 phút bị đánh dấu lease hết hạn; ba run FAILED hoặc REJECTED cùng input thì RETRY_LIMIT. Nếu nhận việc thành công, lưu llm_runs RUNNING và snapshot trước khi gọi mạng.

[GeminiLlmGateway.call dòng 99](D:/HeThong_PhanTichTaiChinh/HE_THONG_DATA_TAI_CHINH_TEST/src/main/java/com/hethongdata/taichinh/service/llm/GeminiLlmGateway.java:99) gọi model và gửi từng attempt về [LlmRunStore.recordAttempt dòng 226](D:/HeThong_PhanTichTaiChinh/HE_THONG_DATA_TAI_CHINH_TEST/src/main/java/com/hethongdata/taichinh/service/llm/LlmRunStore.java:226). Mạng hoạt động ngoài transaction giữ khóa DB. Response cuối được [LlmRunStore.stageResponse dòng 60](D:/HeThong_PhanTichTaiChinh/HE_THONG_DATA_TAI_CHINH_TEST/src/main/java/com/hethongdata/taichinh/service/llm/LlmRunStore.java:60) lưu bền thành PENDING_VALIDATION; cập nhật thất bại do mất quyền xử lý trả LEASE_LOST.

[LlmRunStore.finish dòng 71](D:/HeThong_PhanTichTaiChinh/HE_THONG_DATA_TAI_CHINH_TEST/src/main/java/com/hethongdata/taichinh/service/llm/LlmRunStore.java:71) khóa lại article/task và run, dựng lại context, kiểm tra nguồn, prompt và policy còn phù hợp. [LlmValidationService.evaluate dòng 52](D:/HeThong_PhanTichTaiChinh/HE_THONG_DATA_TAI_CHINH_TEST/src/main/java/com/hethongdata/taichinh/service/llm/LlmValidationService.java:52) đọc luật DB, gọi [NewsLlmValidator.execute dòng 41](D:/HeThong_PhanTichTaiChinh/HE_THONG_DATA_TAI_CHINH_TEST/src/main/java/com/hethongdata/taichinh/service/llm/NewsLlmValidator.java:41), ghi từng kết quả. Chỉ khi không có lỗi chặn mới chuyển SUCCESS, đánh dấu result cũ không current và insert result mới trong transaction.

### 4.3 Validation NEWS

SUMMARY và DETAIL cần chín executor bắt buộc; FACTS cần thêm LLM_FINANCIAL_FACTS. Cấu hình nằm trong validation_rules với data_domain LLM_OUTPUT. Java vẫn phải có code thực thi tương ứng executor_key. Việc đưa luật vào DB không loại bỏ nhu cầu sửa Java khi bổ sung một loại phép kiểm tra mới.

| Luật | Phép kiểm tra thực tế |
|---|---|
| LLM_RESPONSE_READY | HTTP/body/parser đủ để đánh giá response |
| LLM_RESPONSE_SCHEMA | Đúng kiểu, trường bắt buộc, enum, giới hạn của JSON Schema |
| LLM_SOURCE_ID | source_article_id đúng bài đầu vào |
| LLM_EVIDENCE_QUOTES | Quote là chuỗi con nguyên văn của đúng segment |
| LLM_COMPANY_REFERENCES | company_id có trong input |
| LLM_ANALYSIS_COMPLETENESS | Không INSUFFICIENT; sections và limitations đáp ứng điều kiện |
| LLM_COMPANY_IMPACT_UNIQUE | Không lặp một company trong company_impacts |
| LLM_SOURCE_SNAPSHOT | Bài và quan hệ nguồn còn phù hợp khi publish |
| LLM_PROMPT_SNAPSHOT | Prompt còn active và policy còn phù hợp |
| LLM_FINANCIAL_FACTS | Trạng thái phù hợp facts; value_text và trường phụ có trong evidence của chính fact |

Response sai cấu trúc khiến các kiểm tra phụ thuộc bị SKIP; điều này không cho phép publish. ERROR và CRITICAL chặn kết quả. Luật bắt buộc không thể hạ xuống WARNING để bỏ qua điều kiện tối thiểu.

### 4.4 Nhánh lỗi và xử lý lại NEWS

| Trường hợp | Trạng thái hoặc xử lý | Hành động vận hành |
|---|---|---|
| Thiếu body, nguồn trùng hoặc bị loại | SKIPPED, chưa tạo run | Sửa dữ liệu nguồn, không ép AI chạy |
| Response bị chặn hoặc chưa hoàn thành | Không lấy text nếu finishReason khác STOP | Xem response provider và attempt |
| HTTP lỗi hoặc lỗi kỹ thuật validation | FAILED | Kiểm tra quota, quyền, schema/config, mạng |
| HTTP thành công nhưng JSON hoặc bằng chứng sai | REJECTED | Có thể chạy lại trong giới hạn ba run |
| Nguồn đổi trong khi gọi | REJECTED bởi snapshot | Chạy với input mới sau khi nguồn ổn định |
| Response đã lưu nhưng process dừng trước publish | PENDING_VALIDATION | revalidate hoặc validation/pending, không gọi Gemini lại |
| Input giống result current | CACHED | Trả kết quả đã có |
| Run khác đang làm cùng việc | RUNNING | Theo dõi run hiện tại |
| Ba lỗi cùng input | RETRY_LIMIT | Xử lý nguyên nhân; không tự xóa log để vượt giới hạn |
| DETAIL không cần FACTS | NOT_APPLICABLE | Retire FACTS cũ nếu có |

Retry nội dung NEWS có phản hồi lỗi validation từ response REJECTED trước, nhưng giữ input_hash gốc để không reset giới hạn. Không dùng feedback sửa lại trường hợp provider từ chối, response quá dài hoặc nguồn/template đã đổi. Fallback model chỉ xử lý nhóm lỗi vận chuyển/HTTP đã quy định, không tự đổi model chỉ để vượt quyết định chặn nội dung.

[LlmRunStore.revalidate dòng 136](D:/HeThong_PhanTichTaiChinh/HE_THONG_DATA_TAI_CHINH_TEST/src/main/java/com/hethongdata/taichinh/service/llm/LlmRunStore.java:136) dùng response đã lưu để chạy lại luật. Nó không gọi Gemini và không sửa nội dung AI lịch sử. Result hiện hành không đạt thì bị retire. Response từng bị loại có thể được chấp nhận sau sửa policy nếu không có result hiện hành khác; không tự đưa result lịch sử lên đè kết quả mới.

## 5 Financial và market dùng LLM cho việc gì

Phần có công thức xác định phải do Java tính: tỷ lệ tài chính, kiểm tra đơn vị, mẫu số và số tiền theo giả định tăng trưởng. LLM hiện được dùng để đề xuất giả định kịch bản và diễn giải bằng ngôn ngữ. Chưa có bằng chứng hệ thống đã so sánh độ chính xác của giả định này với mô hình thống kê hoặc mô hình tài chính truyền thống.

Task executable là FINANCIAL_SCENARIOS v1. Nó dự phóng từng chỉ tiêu dựa trên giá trị reported cuối cùng, không phải mô hình báo cáo tài chính ba báo cáo được cân đối đồng thời. NEWS và FINANCIAL hiện là hai input tách biệt: forecast không tự đọc llm_results NEWS hay news_articles.

### 5.1 Request admin

| Trường | Kiểu và giới hạn | Ý nghĩa |
|---|---|---|
| securityId | UUID bắt buộc | Chứng khoán đã có trong master data |
| asOfDate | LocalDate, không được ở tương lai theo Việt Nam | Mốc chốt dữ liệu được biết |
| horizonQuarters | Số nguyên 1 đến 8 | Số thứ tự quý mục tiêu theo logic hiện tại; lỗi ngày cuối quý ở phần đánh giá |
| targets | Tập 1 đến 5 enum | Chỉ tiêu cần tạo kịch bản |
| requireMacro | Boolean | true chặn nếu không có quan sát macro |

Ví dụ request thực tế FPT dùng securityId 24ba9d4d-9a84-4315-b52f-a8905d8005bf, asOfDate 2026-10-04, horizonQuarters 1, requireMacro false và đủ năm targets. Kỳ mục tiêu trả về là 2026-12-31.

### 5.2 Dữ liệu được chọn từ DB

[ForecastContextService.build dòng 49](D:/HeThong_PhanTichTaiChinh/HE_THONG_DATA_TAI_CHINH_TEST/src/main/java/com/hethongdata/taichinh/service/forecast/ForecastContextService.java:49) chạy đọc theo REPEATABLE_READ. Cutoff là đầu ngày tiếp theo tại Asia/Ho_Chi_Minh; đối chiếu cả ngày quan sát và thời điểm có dữ liệu để hạn chế lấy thông tin tương lai.

| Nguồn | Điều kiện và giới hạn đang dùng |
|---|---|
| financial_statements và items và periods | Cùng công ty/chứng khoán, current và canonical, VND, unit_scale dương; kỳ Q1 đến Q4 trong năm năm; published_at hoặc created_at và item.created_at trước cutoff |
| INCOME_STATEMENT | NET_PROFIT_AFTER_TAX, PRETAX_PROFIT; không lấy tên trùng của cash flow |
| BALANCE_SHEET | Assets, equity, liabilities, current assets, short-term liabilities, cash and cash equivalents |
| CASH_FLOW | Ba mã dòng tiền hoạt động, đầu tư, tài chính nếu có đúng mapping |
| market_prices | Tối đa 60 close ngày canonical, interval 1d, close dương, trước cutoff |
| financial_metrics | Tối đa 100 bản canonical và VALID, đúng company/security, ngày trong năm năm |
| macro_series và macro_observations | Tối đa 120 quan sát của series active trong 12 tháng, trước cutoff |
| index_prices và security_index_memberships | Chưa được đọc vào ForecastContextService |
| news_articles và kết quả NEWS | Chưa được đưa vào request forecast |

Mỗi điểm gồm id, domain, code, value, unit, periodStart, periodEnd, periodType, scope, availableAt. Tiền tố FSI, PRICE, METRIC, MACRO cho phép truy về bảng gốc. Input còn có company_id, security_id, symbol, company_name, industry, targets, derived_metrics, macro_available, limitations và forecast_basis.

Mỗi target cần ít nhất bốn kỳ khác nhau; cùng target có hai điểm cùng kỳ bị chặn vì nhập nhằng. Báo cáo cuối cũ hơn 450 ngày bị chặn. Dưới 20 phiên hoặc giá cũ hơn 15 ngày là giới hạn được đưa vào prompt. Macro rỗng vẫn có thể chạy khi requireMacro=false, nhưng không được bịa số liệu vĩ mô.

### 5.3 Chỉ số Java tính được

[FinancialRatioCalculator.calculate dòng 14](D:/HeThong_PhanTichTaiChinh/HE_THONG_DATA_TAI_CHINH_TEST/src/main/java/com/hethongdata/taichinh/service/financial/FinancialRatioCalculator.java:14) chỉ ghép số liệu bảng cân đối cùng kỳ và scope, cùng đơn vị. Mã trùng trong nhóm bị loại khỏi phép tính. Thiếu input, mẫu số không dương hoặc tử số âm thì bỏ tỷ lệ đó. Tính BigDecimal với tám số lẻ và HALF_UP.

| Chỉ số | Công thức trong code | Đơn vị và ý nghĩa |
|---|---|---|
| LIABILITIES_TO_EQUITY | LIABILITIES / OWNERS_EQUITY | x; nợ phải trả so với vốn chủ |
| LIABILITIES_TO_ASSETS | LIABILITIES / TOTAL_ASSETS * 100 | phần trăm tài sản tương ứng nợ phải trả |
| EQUITY_TO_ASSETS | OWNERS_EQUITY / TOTAL_ASSETS * 100 | phần trăm tài sản tương ứng vốn chủ |
| CURRENT_RATIO | CURRENT_ASSETS / SHORT_TERM_LIABILITIES | x; tương quan tài sản ngắn hạn và nợ ngắn hạn |
| CASH_RATIO | CASH_AND_CASH_EQUIVALENTS / SHORT_TERM_LIABILITIES | x; tương quan tiền và nợ ngắn hạn |

Đây là công thức đang triển khai; cách diễn giải phải xét ngành. Nợ phải trả không đồng nghĩa toàn bộ nợ vay chịu lãi. Không tự dùng chuẩn doanh nghiệp sản xuất để đánh giá ngân hàng. ROE, ROA, EPS, PE, PB và chỉ báo thị trường khác chưa được bổ sung trong luồng này.

API metrics/recalculate lưu financial_metrics is_derived=true, is_canonical=false, quality_status=WARNING, calculation_version balance-ratios-v1. input_snapshot giữ công thức và điểm nguồn, calculation_key chống trùng. Đã có 95 tỷ lệ FPT; chạy lại cùng nguồn insert 0. LLM không tính thay các tỷ lệ này.

## 6 Bảng đầu ra dự báo và cách hiểu

| Target | Nguồn cơ sở | Đầu ra dự phóng | Điều cần hiểu |
|---|---|---|---|
| NET_PROFIT_AFTER_TAX | Income statement | Ba giá trị lợi nhuận sau thuế bằng VND | Cơ sở riêng quý hoặc lũy kế chưa xác minh |
| PRETAX_PROFIT | Income statement | Ba giá trị lợi nhuận trước thuế bằng VND | Không lấy giá trị từ cash flow cùng tên |
| TOTAL_ASSETS | Balance sheet | Ba giá trị tổng tài sản bằng VND | Chưa ràng buộc đồng thời với nợ và vốn |
| OWNERS_EQUITY | Balance sheet | Ba giá trị vốn chủ bằng VND | Không phải giá trị vốn hóa thị trường |
| LIABILITIES | Balance sheet | Ba giá trị nợ phải trả bằng VND | Tăng nợ không mặc nhiên là kịch bản tốt |

| Trường response | Ý nghĩa và luật hiện tại |
|---|---|
| schema_version | financial.forecast.output.v1 |
| company_id, security_id | Phải khớp request và nguồn |
| as_of_date, forecast_period_end | Mốc chốt thông tin và kỳ mục tiêu |
| status | SUFFICIENT, PARTIAL, INSUFFICIENT; INSUFFICIENT không được publish |
| overview | Diễn giải tổng quan |
| forecasts[].metric_code | Mỗi target xuất hiện đúng một lần, tập target phải khớp request |
| base_point_id | Điểm mới nhất của đúng target; giá trị phải dương |
| bear/base/bull.growth_percent | Giả định biến động phần trăm so với base point, giới hạn từ -100 đến 300 |
| bear/base/bull.rationale | Giải thích giả định bằng tiếng Việt |
| bear/base/bull.evidence_ids | ID phải tồn tại; gồm base mới nhất và ít nhất một điểm lịch sử trước đó của cùng mã chỉ tiêu |
| macro_used | Phải phù hợp với việc trích ID macro trong evidence |
| risks và limitations | Rủi ro, giới hạn; không được xem là facts đã được kiểm chứng độc lập |
| calculated_values[] | Java bổ sung sau validation, chứa giá trị ba kịch bản, đơn vị, công thức, base và kỳ |

Hiện code yêu cầu growth bear ≤ base ≤ bull cho mọi target. Do đó với LIABILITIES, tên bear/bull đang thể hiện mức số học thấp/cao, chưa nhất quán với tác động tốt/xấu lên công ty. Đây là điểm BA phải chốt lại trước khi FE đặt nhãn “bi quan/lạc quan”.

[ForecastValidationService.projections dòng 215](D:/HeThong_PhanTichTaiChinh/HE_THONG_DATA_TAI_CHINH_TEST/src/main/java/com/hethongdata/taichinh/service/forecast/ForecastValidationService.java:215) thực hiện phép tính base_value * (1 + growth_percent / 100). Ví dụ số học minh họa, không phải dự báo thật: base_value 100 và growth 5 cho ra 105. Growth là giả định LLM; phép nhân do Java thực hiện. Mỗi dòng lưu calculation_version scenario-v1 và forecast_basis UNCALIBRATED_SCENARIO_RELATIVE_TO_REPORTED_VALUE_NOT_TTM, nghĩa là kịch bản chưa hiệu chuẩn so với giá trị reported, không phải TTM.

Ba giá trị không phải phân vị thống kê hoặc khoảng tin cậy. Không có xác suất kịch bản trong contract forecast. Không được diễn giải value_text FORECAST của NEWS thành một dự báo do mô hình này sinh ra.

## 7 Financial đi qua các phương thức nào

[FinancialForecastAdminController.execute dòng 86](D:/HeThong_PhanTichTaiChinh/HE_THONG_DATA_TAI_CHINH_TEST/src/main/java/com/hethongdata/taichinh/controller/admin/FinancialForecastAdminController.java:86) nhận ForecastRequest có Bean Validation, gọi [FinancialForecastService.execute dòng 62](D:/HeThong_PhanTichTaiChinh/HE_THONG_DATA_TAI_CHINH_TEST/src/main/java/com/hethongdata/taichinh/service/forecast/FinancialForecastService.java:62). Interceptor [ForecastAdminAccessConfiguration.addInterceptors dòng 28](D:/HeThong_PhanTichTaiChinh/HE_THONG_DATA_TAI_CHINH_TEST/src/main/java/com/hethongdata/taichinh/config/ForecastAdminAccessConfiguration.java:28) kiểm tra khóa admin cho riêng namespace /api/admin/forecasts. Credential thiếu cấu hình trả 503; không đúng trả 403.

Service gọi [ForecastContextService.build dòng 49](D:/HeThong_PhanTichTaiChinh/HE_THONG_DATA_TAI_CHINH_TEST/src/main/java/com/hethongdata/taichinh/service/forecast/ForecastContextService.java:49), trong đó gọi FinancialRatioCalculator.calculate. Thiếu hoặc nhập nhằng nguồn trả SKIPPED trước khi tạo run. [ForecastPromptService.active dòng 60](D:/HeThong_PhanTichTaiChinh/HE_THONG_DATA_TAI_CHINH_TEST/src/main/java/com/hethongdata/taichinh/service/forecast/ForecastPromptService.java:60) lấy template và [ForecastValidationService.fingerprint dòng 63](D:/HeThong_PhanTichTaiChinh/HE_THONG_DATA_TAI_CHINH_TEST/src/main/java/com/hethongdata/taichinh/service/forecast/ForecastValidationService.java:63) lấy định danh bộ luật. Gateway thiếu cấu hình trả CONFIGURATION_REQUIRED.

[ForecastRunStore.claim dòng 39](D:/HeThong_PhanTichTaiChinh/HE_THONG_DATA_TAI_CHINH_TEST/src/main/java/com/hethongdata/taichinh/service/forecast/ForecastRunStore.java:39) khóa theo security, tìm cache và run đang xử lý, áp dụng giới hạn ba lần lỗi cho cùng input. Nó lưu forecast_request, input, coverage, source_hash, validation_policy, template_checksum và routing_key. Sau đó gateway.call chạy ngoài transaction, ghi từng attempt qua ForecastRunStore.attempt.

[ForecastRunStore.stage dòng 130](D:/HeThong_PhanTichTaiChinh/HE_THONG_DATA_TAI_CHINH_TEST/src/main/java/com/hethongdata/taichinh/service/forecast/ForecastRunStore.java:130) lưu response và chuyển PENDING_VALIDATION. [ForecastRunStore.validate dòng 182](D:/HeThong_PhanTichTaiChinh/HE_THONG_DATA_TAI_CHINH_TEST/src/main/java/com/hethongdata/taichinh/service/forecast/ForecastRunStore.java:182) khóa run, đọc snapshot đã gửi, dựng lại nguồn hiện tại, kiểm tra template và policy, rồi gọi [ForecastValidationService.evaluate dòng 67](D:/HeThong_PhanTichTaiChinh/HE_THONG_DATA_TAI_CHINH_TEST/src/main/java/com/hethongdata/taichinh/service/forecast/ForecastValidationService.java:67). JSON hợp lệ được projections bổ sung số tiền bằng Java và publish sang llm_results source_domain FINANCIAL.

| Luật forecast | Nội dung |
|---|---|
| FORECAST_SCHEMA | Response đủ điều kiện, đúng JSON Schema |
| FORECAST_SOURCE | Company, security và ngày khớp snapshot |
| FORECAST_EVIDENCE | ID điểm có thật, base mới nhất, có lịch sử |
| FORECAST_SCENARIOS | Target đúng, không trùng, base dương và thứ tự tăng trưởng |
| FORECAST_COVERAGE | Nguồn đủ điều kiện, không INSUFFICIENT, giới hạn và macro nhất quán |
| FORECAST_SNAPSHOT | Nguồn hiện tại vẫn đủ và hash khớp |
| FORECAST_PROMPT | Template và bộ luật còn phù hợp |

Các luật nằm trong validation_rules data_domain LLM_FORECAST, cùng ghi validation_results validation_target LLM_OUTPUT. FORECAST_EVIDENCE và FORECAST_SCENARIOS hiện dùng chung khối logic, vì vậy không nên xem bảy dòng PASS là bảy lớp xác minh kinh tế độc lập.

### 7.1 Các trường hợp forecast

| Trường hợp | Kết quả hiện tại |
|---|---|
| Security/date/target/horizon sai | Lỗi request hoặc IllegalArgumentException qua error handler |
| Dưới bốn kỳ, trùng kỳ hoặc stale financial | SKIPPED với issues |
| requireMacro=true và macro rỗng | SKIPPED, MACRO_REQUIRED_BUT_MISSING |
| Chưa có template active | Lỗi cấu hình/argument, không gọi provider |
| Bộ luật thiếu hoặc sai | VALIDATION_CONFIGURATION_ERROR |
| Đã có result đúng input | CACHED |
| Cùng security có run chưa kết thúc | RUNNING, kể cả ngày hoặc target khác |
| Lỗi provider hoặc technical | FAILED |
| Sai schema, nguồn, evidence, growth | REJECTED |
| Nguồn/template đổi lúc đang chạy | Không publish snapshot cũ |
| Response chờ validate | API validation/pending hoặc runs/id/revalidate phục hồi |
| Một pending run lỗi khi recovery | RECOVERY_ERROR, vòng xử lý vẫn tiếp tục run khác |

[ForecastRunStore.results dòng 394](D:/HeThong_PhanTichTaiChinh/HE_THONG_DATA_TAI_CHINH_TEST/src/main/java/com/hethongdata/taichinh/service/forecast/ForecastRunStore.java:394) chỉ trả result current có template active, policy hiện hành, nguồn còn eligible và hash khớp, đủ bảy PASS của vòng liên kết. Kết quả forecast hiện đều WARNING do giới hạn dữ liệu và chưa hiệu chuẩn.

Mỗi security/task/as_of_date chỉ có một result current. Chạy thành công bộ target hoặc horizon khác cùng asOfDate sẽ retire result trước dù kỳ dự báo khác. Lịch sử còn trong DB nhưng API current không trả đồng thời mọi horizon. Nếu dashboard cần so sánh nhiều kỳ, cần mở rộng khóa nghiệp vụ và API đọc lịch sử.

## 8 Provider và các giới hạn gọi chung

[GeminiLlmGateway.request dòng 71](D:/HeThong_PhanTichTaiChinh/HE_THONG_DATA_TAI_CHINH_TEST/src/main/java/com/hethongdata/taichinh/service/llm/GeminiLlmGateway.java:71) tạo systemInstruction, contents, responseMimeType JSON, temperature 0.1, responseJsonSchema dạng provider hỗ trợ. Java kiểm tra schema đầy đủ sau khi nhận, vì schema gửi provider đã bỏ bớt một số giới hạn.

[GeminiLlmGateway.call dòng 99](D:/HeThong_PhanTichTaiChinh/HE_THONG_DATA_TAI_CHINH_TEST/src/main/java/com/hethongdata/taichinh/service/llm/GeminiLlmGateway.java:99) có tối đa tám attempts, một attempt mỗi model theo cấu hình, timeout mỗi lần 45 giây và tổng một tác vụ 180 giây. Backoff có jitter từ cấu hình 1 đến tối đa 8 giây. 404, 408, 429, 500, 502, 503, 504 và IOException có thể chuyển model; 400, 401, 403 và quyết định nội dung không thuộc nhóm này là terminal. 404/429 đặt cooldown cho model, có đọc Retry-After. Cooldown nằm trong memory của từng instance.

Response chỉ lấy text của candidate đầu tiên khi finishReason STOP và bỏ phần thought. Không có text hợp lệ thì vẫn giữ response để audit. API key nằm trong header gửi provider; audit không lưu header này. Run và attempt có thể chứa toàn bộ bài và dữ liệu tài chính nên API đọc log cần được bảo vệ.

Các giới hạn trên áp dụng cho một task, không phải một lần executeAll ba task. Request đồng bộ chạy đủ ba task có thể kéo dài nhiều phút. Chưa có hàng đợi worker riêng, ngân sách token toàn hệ thống hoặc giới hạn đồng thời toàn cluster.

## 9 Lưu trữ và truy vết

| Bảng | Quan hệ hoặc thông tin chính | Cách sử dụng |
|---|---|---|
| llm_prompt_templates | task_code, version, domain, prompt, request_schema, response_schema, checksum, enabled | Catalog runtime; file resource phục vụ seed có kiểm soát |
| llm_runs | prompt_template_id; news_article_id hoặc company_id/security_id; request/response và state | Điều phối và điều tra một tác vụ |
| llm_run_attempts | llm_run_id, attempt_no, model, HTTP, token, latency | Xác định fallback, lỗi provider và tiêu thụ |
| validation_rules | domain, executor_key, rule_config, severity, is_active | Điều khiển luật, không thay thế mã executor |
| validation_results | llm_run_id, validation_round_id, snapshot luật, PASS/FAIL/SKIP | Audit từng vòng; kết quả raw dùng quan hệ riêng |
| llm_results | run, template, task, domain, source hash, input hash, JSON, quality, is_current | Dữ liệu đã qua kiểm tra để trả frontend |
| financial_metrics | Tỷ lệ Java tính từ số liệu nguồn, input_snapshot và calculation_key | Giữ phép tính xác định, tách khỏi dự báo AI |

Không tạo ingestion_run, raw_payload hay data_version mới cho response Gemini. Trạng thái llm_runs và vòng validation phục vụ phần việc tương đương, còn llm_results là nơi công bố kết quả. Một bài có thể có ba result current tương ứng ba tác vụ và nhiều bản lịch sử.

## 10 API phục vụ vận hành

| Nhóm API | Endpoint | Mục đích |
|---|---|---|
| NEWS | GET /api/admin/llm/templates | Xem prompt active và schema |
| NEWS | GET /api/admin/llm/news/candidates | Danh sách bài có thể xử lý |
| NEWS | GET /api/admin/llm/news/{id}/preview | Xem input và provider request |
| NEWS | POST /api/admin/llm/news/{id}/execute | Chạy các task theo thứ tự |
| NEWS | POST /api/admin/llm/news/{id}/tasks/{task}/execute | Chạy một task |
| NEWS | GET /api/admin/llm/news/{id}/results | Kết quả hiện hành hợp lệ |
| NEWS | GET /api/admin/llm/runs/{id} | Request, response, attempts và validation |
| NEWS | POST /api/admin/llm/runs/{id}/revalidate | Kiểm tra response đã lưu |
| NEWS | POST /api/admin/llm/validation/pending | Phục hồi các run chờ kiểm tra |
| Forecast | GET /api/admin/forecasts/configuration và /securities | Cấu hình an toàn và danh mục chứng khoán |
| Forecast | POST /api/admin/forecasts/preview và /execute | Kiểm tra input và thực hiện tác vụ |
| Forecast | GET /api/admin/forecasts/template và /templates | Prompt active và danh sách version |
| Forecast | POST /api/admin/forecasts/template/seed | Seed template bất biến từ resource |
| Forecast | PATCH /api/admin/forecasts/template/{id}/enabled | Bật hoặc tắt template |
| Forecast | POST /api/admin/forecasts/metrics/recalculate | Tính và lưu tỷ lệ bằng Java |
| Forecast | GET /api/admin/forecasts/securities/{id}/metrics | Đọc tỷ lệ và nguồn phép tính |
| Forecast | GET /api/admin/forecasts/securities/{id}/results và /runs | Đọc kết quả và lịch sử run |
| Forecast | GET /api/admin/forecasts/runs/{id} | Chi tiết run, attempts, validation |
| Forecast | POST /api/admin/forecasts/runs/{id}/revalidate | Kiểm tra lại response |
| Forecast | POST /api/admin/forecasts/validation/pending | Recovery không gọi lại model |

Forecast sử dụng ForecastRequest và các record trong ForecastDtos. NEWS còn nhiều JsonNode và Map; BA và FE phải dựa vào task/schema version để diễn giải. GET không có nghĩa response là dữ liệu công khai: preview và runs có thể lộ nội dung nguồn và prompt.

## 11 Bằng chứng và quy trình đọc lỗi

Snapshot kiểm tra lại lúc khoảng 16:51 giờ Việt Nam ngày 04/10/2026: 359 news_articles, 358 bài có ít nhất 200 ký tự, một bài không có body, không có bài thiếu published_at, không trùng url_hash, không còn nguyên cụm menu CafeF đã biết. Những phép đếm này không thay thế kiểm duyệt ngữ nghĩa của 358 bài.

DB có 40 llm_runs, 57 attempts và 22 results lịch sử. Có sáu NEWS result current và năm FINANCIAL result current cho FPT, ACB, HPG, BID, VCB. Năm forecast đều SUCCESS/WARNING và bảy PASS mỗi vòng hiện hành. Hai bảng macro rỗng; index_prices có 3736 bản, security_index_memberships rỗng. Có 328 báo cáo tài chính, 300 current/canonical; report_scope đều UNKNOWN và không có published_at.

Lần kiểm thử triển khai trước đã đạt 123 tests và gọi Gemini thật cho năm mã. Lần đánh giá này đối chiếu lại DB, đọc response đã lưu, thử GET quyền truy cập, gọi Python health/url-fetch và gọi forecast preview để tái hiện lỗi ngày cuối quý. Không chạy lại ingestion ghi dữ liệu hoặc tạo thêm kết quả AI trong đợt đánh giá này.

Một response URL thực tế trả HTTP 200 ở API Python nhưng http_status của nguồn là 404, extraction_status SKIPPED, không có body bài. Java chỉ có nhánh URL_ONLY cho FAILED. Vì vậy việc giữ URL chưa bao phủ mọi trạng thái lỗi từ hệ thống đang chạy. Health báo 0.4.0 không phải căn cứ tự nó để kết luận lỗi; chính hợp đồng response mới là bằng chứng.

Để điều tra một tác vụ: đọc Outcome → tìm runId → xem attempt cuối và các attempt fallback → xem response_text → xem vòng validation mà result liên kết → đối chiếu source hash, template, policy và dữ liệu nguồn. Không chỉ nhìn HTTP 200 hoặc một dòng SUCCESS trong log.

Trước production cần xử lý các mục P0/P1 trong báo cáo đánh giá, đặc biệt xác thực admin, URL fallback SKIPPED, kỳ mục tiêu, quy tắc dùng số liệu lũy kế và ngữ nghĩa kịch bản. Phạm vi thay đổi của đợt bàn giao này là tài liệu, script kiểm tra chỉ đọc và chú thích điều phối trong hai service; các lỗi nghiệp vụ chưa được sửa trong đợt đánh giá.
