# API user: ghép chức năng trong workspace — 23/10/2026

Nhánh `feature/user-api`, tiếp tục từ `58a03e8`. Chỉ commit/push nhánh theo yêu cầu;
không tạo PR, merge hoặc deploy. Chỉ sửa checkout Java `_training_data_java`.
Không sửa checkout gốc đang làm Local AI, FE admin hoặc Python đang chạy.

## Đối chiếu và ranh giới tích hợp

Đã đọc cấu trúc, controller, luồng gọi và hợp đồng của các dự án liên quan trong
workspace; đọc trạng thái chat **DỰ ÁN LOCAL AI - TÁCH TỪ LUỒNG LLM**,
**PHÁT TRIỂN FLOW LLM** và **Thiết kế UI UX admin và user**. Không gửi lệnh
hoặc thay đổi công việc của các chat đó.

| Nguồn đối chiếu | Phần đưa vào backend hiện tại | Phần chưa đưa vào |
| --- | --- | --- |
| `FINANCIAL_BACKEND_TEST_V1` + `FINANCIAL_FRONTEND_TEST_V1` | Danh sách công ty, chọn kỳ báo cáo, bảng tài chính, trạng thái dữ liệu, màn chi tiết tổng hợp | ID số/SQL Server, DTO cũ, provider fixture, chức năng ghi và tài khoản mẫu |
| `FINANCIAL_CONTRACTS_TEST_V1` + `FINANCIAL_INFRA_TEST_V1` | Phân biệt thời gian quan sát/công bố, nguồn dữ liệu, mức sẵn sàng, hợp đồng API | SLA freshness và giả định schema khác chưa được chứng minh cho DB này |
| `FINANCIAL_AI_SERVICE_TEST_V1` | Phân biệt catalog model và dữ liệu được phép công bố | API train/predict, điểm tổng hợp và trọng số nhận từ request/demo không trở thành điểm thật |
| `HE_THONG_DATA_TAI_CHINH_AI_LOCAL` + tài liệu Local AI ngày 21/10 ở checkout gốc | Trạng thái registry thực trong DB, báo rõ chưa có reader công bố dự đoán Local AI trong build này | Artifact nghiên cứu, retrospective outcomes, fact NPAT RESEARCH_ONLY, engine và schema đang được AI khác sửa |
| FE admin + luồng NEWS/forecast hiện có | Giữ phân quyền admin; dùng lại reader kiểm định gốc để đọc kết quả cho user | Không đưa admin catalog, prompt, attempts, raw request hoặc báo cáo có `user_id` ra API công khai |

Các dự án `FINANCIAL_*_TEST_V1` đối chiếu là thư mục demo local, không có Git metadata
tại thời điểm đọc. Không thể coi chúng là một bản phát hành upstream có version.
FE demo chưa gọi đúng DTO/UUID của backend hiện tại: cần adapter hoặc cập nhật FE
theo hợp đồng dưới đây; chưa nghiệm thu giao diện user hoàn chỉnh.

## Hợp đồng chung và 14 GET mới

Hiện có **33 GET công khai** dưới `/api/v1`: 19 route trong
[hướng dẫn gốc](USER_API_20261020.md) và 14 route dưới đây.
Giữ envelope `data/meta` hoặc `items/total/page/size/hasNext/meta`.
Số tiền, decimal và bigint ID dùng chuỗi; tham số trang và tổng số trang vẫn là số.
Không bịa giá trị, ngày công bố, score hoặc độ chính xác model.

| GET (sau `/api/v1`) | Tham số chính | Response / giới hạn |
| --- | --- | --- |
| `/companies` | `search`, `activeOnly=true`, `page=0`, `size=25` | Công ty theo UUID, phân biệt với security UUID; size tối đa 100 |
| `/market/filters` | Không | Sàn/ngành thực có trong master đang hoạt động và enum filter; số lượng chỉ các mã theo dõi |
| `/securities/{symbol}/data-status` | `exchange` | Coverage giá ngày, báo cáo, metrics, news; mốc nguồn/lưu, hạn chế metadata; `pointInTimeCertified=false` |
| `/securities/{symbol}/periods` | `exchange` | Kỳ thực có báo cáo current/canonical/ACTIVATED, mới trước; trên 500 kỳ trả 422 |
| `/securities/{symbol}/statements/{id}/lineage` | `exchange`, UUID báo cáo | Nguồn/version ACTIVATED/thời điểm fetch-công bố-lưu; cùng điều kiện sở hữu/công bố của report detail |
| `/securities/{symbol}/statement-items` | `exchange`, `type=INCOME_STATEMENT`, `page=0`, `size=50` | Mã/tên/đơn vị chỉ tiêu thực có cho công ty và loại báo cáo; size tối đa 100 |
| `/securities/{symbol}/financials` | `exchange`, `type=INCOME_STATEMENT`, `period=QUARTER`, `scope`, **`codes`**, `from`, `to`, `periodLimit=12` | Ma trận tối đa 40 kỳ × 20 mã; cửa sổ mặc định 10 năm/tối đa 30; kỳ tăng dần |
| `/market/screener` | `search`, `exchange`, `industry`, `metric`, `origin=PROVIDER`, `asOf`, `min`, `max`, `sort=SYMBOL`, `page=0`, `size=25` | Mã đang hoạt động + metric mới nhất đến asOf; size tối đa 100 |
| `/securities/compare` | **`symbols`**, `exchange`, **`codes`**, `origin=PROVIDER`, `asOf` | Tối đa 10 mã × 20 metric, định nghĩa/đơn vị/ngày riêng, `unknownCodes` |
| `/securities/{symbol}/foreign-trades` | `exchange`, `from`, `to`, `page=0`, `size=50` | Quan sát canonical + ACTIVATED, thứ tự ngày tăng, SHARES; thiếu khối lượng giữ null |
| `/securities/{symbol}/reference-observations` | `exchange`, `from`, `to`, `page=0`, `size=25` | Snapshot tham chiếu ACTIVATED, quan sát mới trước; ngày hiệu lực giữ riêng |
| `/analysis/availability` | Không | Trạng thái reader NEWS/scenario và registry Local AI nếu schema có; không gọi model |
| `/securities/{symbol}/overview` | `exchange` | Profile, quote, data-status, 3 báo cáo, 10 metric observations, 3 tin, tối đa 3 scenario candidates và trạng thái AI |
| `/openapi.json` | Không | OpenAPI 3.1 từ GET handler user thực đăng ký trong build; không có route admin/ghi |

`exchange` cần truyền khi symbol có nhiều listing, nếu không trả 409.
Size thông thường 1–100, offset tối đa 10000; các route cũ giữ giới hạn riêng.
Foreign/reference dùng cửa sổ mặc định 10 năm, tối đa 30 năm.
Reference lọc theo ngày `observedAt` Việt Nam, không coi snapshot master là lịch sử ngành/cổ phiếu lưu hành.

## Ý nghĩa quan trọng cho FE

**Data status**: `PARTIAL` khi thiếu nhóm giá/báo cáo/metrics hoặc metadata công bố,
scope, period basis. Nếu không phát hiện các thiếu hụt đó vẫn chỉ là
`AVAILABLE_WITH_UNVERIFIED_HISTORY`. Coverage không đếm số phiên/kỳ đáng lẽ phải có,
không chứng nhận đủ 10 năm, freshness SLA hoặc point-in-time. News có coverage riêng,
không dùng số lượng news để suy ra đủ dữ liệu train.

**Lineage**: URL nguồn có credentials, query hoặc fragment sẽ trả null để không
đưa token từ URL raw lên giao diện. Không xuất raw body/header/request parameters.
URL-only của tin vẫn theo hợp đồng news cũ.

**Financial matrix**:

- Dùng `statement-items` để lấy mã nguồn đúng, không giả định mọi công ty có cùng mã.
- `period=QUARTER` chỉ Q1–Q4; FY phải chọn riêng. Có thể chọn Q1/Q2/Q3/Q4 riêng.
- `scope` tùy chọn: CONSOLIDATED, SEPARATE, UNKNOWN.
- `periods` có UUID và mốc kỳ. Mỗi `series.values[periodId]` chứa `value`,
  `statementId`, `sourceCode`, `publishedAt`.
- Mỗi series tách theo code/scope/basis/currency/unit/unitScale. Không trộn hợp nhất,
  riêng lẻ, lũy kế/đơn quý hoặc đơn vị. Giá trị là số đã lưu; không tự nhân unitScale.
- Thiếu cell thì key không có; `missingCodes` là mã không thấy trong các kỳ đã chọn.
  Không thay bằng 0, không tính TTM/annualize. `hasEarlierPeriods` chỉ trong bộ lọc.
- Nhiều canonical cell cùng khóa trả 409, không chọn ngẫu nhiên bằng UUID.

**Screener/compare**:

- `origin=PROVIDER|DERIVED` tách nguồn và phép tính Java. Trả `derived` và
  `calculationVersion` khi xác định được bản duy nhất.
- Chọn metric canonical VALID/WARNING có version ACTIVATED, hoặc derived không có
  version như reader metric hiện hành; không đọc bản rejected.
- `asOf` mặc định ngày Việt Nam hiện tại. Đây là ngày metric lưu, không phải chứng
  nhận dữ liệu đã được công bố tại thời điểm quá khứ.
- Nếu nhiều candidate cùng ngày mới nhất: `metricState=AMBIGUOUS`,
  `candidateCount>1`, `value=null`. Không có bản ghi: `MISSING`.
  `AVAILABLE` vẫn có thể là metric text với value số null.
- `min/max` cần `metric`, nhận decimal hữu hạn (precision ≤38, scale ≤12);
  missing/ambiguous không qua được lọc số. `min>max` trả 400.
- `sort` chỉ SYMBOL, METRIC_ASC, METRIC_DESC. Sort metric cần metric; null cuối.
  `industry` so khớp tên ngành chính xác từ filters. Không có sort/SQL tùy ý.
- Screener metric không tồn tại trả 404; compare giữ `unknownCodes` để FE giải thích.
  Không tạo điểm sức khỏe tổng hợp, target price hay khuyến nghị từ trọng số demo.

**Overview/AI**: các section đi chung transaction read-only/repeatable-read.
Metric section là 10 observation mới nhất, không phải một giá trị cho mỗi code;
dùng compare khi cần latest theo code. Mỗi section giữ metadata/paging riêng.
`financialScenarioState=NO_ELIGIBLE_RESULT` không phân biệt chưa chạy, hết hiệu lực
hay bị reader chặn. Không gọi model/revalidation để tự lấp kết quả rỗng.

Local AI trả `publicPredictionReader=NOT_INTEGRATED`,
`reason=PUBLICATION_POLICY_REQUIRED`. Registry có thể thay đổi bởi chat khác;
đây là thống kê registry dùng chung `model_versions`, không phải chứng nhận
đã tích hợp/công bố mọi gói model trong thư mục Local AI.
TRAINED/ACTIVE không tự đồng nghĩa với đủ điều kiện công bố user. Không đọc output
nghiên cứu, artifact path, source snapshot, hyperparameters hoặc NPAT RESEARCH_ONLY.
Khi luồng đó được hoàn thiện cần nối reader công bố chính thức và nghiệm thu riêng;
không phải chỉ đổi cờ hoặc trả trực tiếp bảng predictions.

**OpenAPI** có query/path/default/required/kiểu ISO date/UUID, envelope và lỗi.
Các projection Map/JSON còn linh hoạt vì field nguồn khác nhau; không phải schema
typed chi tiết mọi metric/model payload. Dùng tài liệu và `meta.notes` cùng contract.
Thiếu query bắt buộc trả 400 có tên tham số, không rơi vào lỗi 500.

## Kiểm chứng ngày 23/10

- **209 unit/contract pass**, 0 failure/error/skipped, `mvn verify` đóng gói được.
- **12 PostgreSQL integration pass** trong schema tạm `user_api_test_<UUID>`;
  clone cấu trúc và fixture riêng, không copy row public hoặc dùng sequence public.
  Có case ambiguity metric, số lớn, scope matrix, nguồn rejected, null foreign volume,
  observedAt qua ranh giới ngày Việt Nam và URL raw chứa credential.
- Unit test Local AI dùng mock registry, chỉ chứng minh không suy ACTIVE thành
  được phép xuất dự đoán. Test AI projection cũ dùng mock store, không gọi model thật.
- **48 HTTP request pass, bao phủ 33 GET**, localhost port 8093 dùng DB thật,
  Hikari read-only, lazy init, model/catalog seed/scheduler tắt. Có CORS từ
  `http://localhost:5173`, input 400, unknown/unpublished 404, POST 405 và admin thiếu token.
  Dùng [script nghiệm thu](../scripts/user_api_smoke.py), không gửi credential admin.
  Lượt cuối chạy trên jar cuối cùng ghi nhận tối đa 516ms/request (đo local, không là SLA).
  Đã dừng runtime thử nghiệm sau nghiệm thu. Workflow deploy chỉ chạy thủ công trên main;
  không được kích hoạt trong công việc này.
- Không kiểm thử UI user đã tích hợp vì FE demo khác contract và chat UI đang thiết kế;
  đã xác minh ranh giới HTTP/CORS → service → DB → response. Không chạy load test.
- Không migration public, backfill, provider fetch, train, LLM, thay đổi dữ liệu cũ,
  merge/deploy hay kiểm tra feature trên production chưa chứa nhánh này.

Chạy lại với runtime đã được cấu hình chỉ đọc và tắt worker/model:

```text
python scripts/user_api_smoke.py --base http://127.0.0.1:8093 --report target/user-http.json
```

Script yêu cầu DB có dữ liệu thật của symbol/sàn chọn (mặc định FPT/HOSE), ít nhất
một index, macro series và tin công khai. Report chỉ lưu metadata request/count,
không lưu token, cấu hình private hoặc raw response vào repo.
