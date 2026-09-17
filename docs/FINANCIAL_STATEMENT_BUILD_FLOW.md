# Luồng xử lý FINANCIAL_STATEMENT_BUILD

## Mục tiêu

`FINANCIAL_STATEMENT_BUILD` là internal ingestion job tiêu thụ duy nhất các batch báo cáo tài chính đã qua validation. Nó không gọi provider/Python và không đọc raw chưa validate.

Vocabulary trạng thái trong database hiện tại:

- `ACTIVE`: DataVersion đã validate và chờ workflow downstream tiêu thụ.
- `ACTIVATED`: workflow đã persist đầy đủ dữ liệu chuẩn hoá cho DataVersion đó.

## Luồng nghiệp vụ

```text
FINANCIAL_STATEMENT raw payloads
        │
        ▼
validation hiện có
        │
        ▼
DataVersion(FINANCIAL_STATEMENT, ACTIVE)
        │
        ▼
FINANCIAL_STATEMENT_BUILD
        │
        ├── ingestion_run_id của DataVersion
        │       │
        │       ▼
        │   raw_payloads(entity_type = FINANCIAL_STATEMENT)
        │
        ├── symbol → Security → Company
        ├── dataset → statement_type
        ├── fiscal date / period key → FinancialPeriod
        │
        ▼
financial_statements
        │
        ▼
financial_statement_items
        │
        ▼
DataVersion: ACTIVE → ACTIVATED
```

Đây khớp với luồng yêu cầu: `data_versions` là điểm vào, `ingestion_run_id` là khóa xác định batch raw đã validate, và chỉ khi toàn bộ statement/item của **một DataVersion** được commit thì version đó mới được `ACTIVATED`.

## Chọn input

`FinancialStatementBuildService.execute` query theo thứ tự tạo:

```text
data_domain = FINANCIAL_STATEMENT
status      = ACTIVE
```

Với mỗi version được chọn, service chỉ đọc:

```text
raw_payloads.ingestion_run_id = data_versions.ingestion_run_id
raw_payloads.entity_type      = FINANCIAL_STATEMENT
```

Do đó raw của run khác, raw chưa validate và domain khác không thể bị Job tiêu thụ nhầm.

## Parse và chuẩn hoá

### Symbol

Ưu tiên `payload.symbol`/`payload.code`, fallback `raw_payloads.source_symbol`.

`security_id` trên raw được dùng nếu có; nếu không, resolve qua `securities.symbol` không phân biệt hoa/thường. Security phải có `company_id`; nếu không tìm được mapping thì DataVersion đó fail và giữ `ACTIVE`.

### Dataset → StatementType

| Raw dataset | `financial_statements.statement_type` |
| --- | --- |
| `income_statement`, `income` | `INCOME_STATEMENT` |
| `balance_sheet`, `balance` | `BALANCE_SHEET` |
| `cash_flow`, `cashflow` | `CASH_FLOW` |

Dataset ngoài bảng bị coi là lỗi batch, không có dữ liệu partial được commit.

### Fiscal period

Parser nhận ba dạng payload:

1. VNDIRECT array row-based dùng `fiscalDate = 2026-06-30` và `reportType = QUARTER`.
2. VNStock array row-based: period nằm trực tiếp trên từng row, không nằm trong object con. Ví dụ:

   ```json
   {
     "item": "5. Lợi nhuận gộp về bán hàng và cung cấp dịch vụ",
     "item_id": "gross_profit",
     "2025-Q3": 4278629180000,
     "2025-Q4": 6301347904000,
     "2026-Q1": 7054150330000,
     "2026-Q2": 6518866479000
   }
   ```

   Parser nhận diện mọi key có dạng `YYYY-Q1..Q4` và transpose từng row thành item của từng period. Với 25 row và 4 period, kết quả là 4 FinancialStatements, mỗi statement nhận các item của đúng period đó.
3. Object period-keyed tổng quát, ví dụ `gross_profit: { "2026-Q2": 6518866479000 }`.

Ví dụ `2026-Q2` được chuẩn hoá thành:

```text
fiscal_year = 2026
period_type = Q2
start_date  = 2026-04-01
end_date    = 2026-06-30
```

Job tìm `financial_periods` theo `(fiscal_year, period_type, end_date)`. Nếu chưa có thì tạo period cùng các date đã parse; nếu đã có thì reuse `financial_period_id` hiện có.

### Statement và item

Mỗi cặp `(raw payload, period)` tạo một FinancialStatement với:

- `company_id`, `security_id`
- `financial_period_id`, `statement_type`, `report_scope`
- `data_source_id`, `raw_payload_id`, `data_version_id`
- metadata chuẩn mặc định (`VND`, scale `1`, revision `1`, current `true`).

Mỗi accounting row của kỳ trở thành FinancialStatementItem. Ví dụ:

```text
gross_profit / 2026-Q2 = 6518866479000
        ↓
FinancialStatement(FPT, INCOME_STATEMENT, 2026-Q2)
        ↓
FinancialStatementItem(item_code=GROSS_PROFIT, value=6518866479000)
```

Provider có thể có nhiều accounting row khác nhau cùng `item_id`, ví dụ hai row cùng `item_id = revenue`. Khi một base item code bị trùng trong cùng statement, Job tạo mã `BASE_<12_HEX_SHA256>`; fingerprint SHA-256 được tính từ `source_item_id + source_item_name`, không phụ thuộc thứ tự row provider. Vì vậy hai row `revenue` có tên nguồn khác nhau có item_code khác nhau nhưng một payload được đảo thứ tự vẫn sinh đúng cùng mapping.

Mọi item vẫn được bảo toàn. `financial_statement_items.item_name` giữ source item name; metadata giữ `sourceItemId`, `sourceItemName` và `provider`.

## Transaction và failure semantics

`FinancialStatementBuildPersistenceService.persist` có `@Transactional` và lock lại DataVersion trước khi ghi. `IngestionRunRepository.startInternalBatch` tạo/persist run `RUNNING` trong transaction riêng trước processing. Khi persistence throw exception, transaction persist rollback; sau đó `FinancialStatementBuildService` (nằm ngoài transaction này) gọi `IngestionRunRepository.markFailed`, cũng là transaction riêng. Vì vậy FAILED run không bị rollback cùng statement/item.

Trong một transaction, service:

1. Kiểm tra version vẫn là `FINANCIAL_STATEMENT / ACTIVE`.
2. Resolve Security/Company, FinancialPeriod.
3. Persist tất cả FinancialStatement và FinancialStatementItem của version.
4. Mark internal IngestionRun `SUCCESS`.
5. Chuyển DataVersion sang `ACTIVATED`.

Nếu bất kỳ bước nào lỗi, transaction rollback statement/item/period mới của batch đó và DataVersion giữ `ACTIVE`. Service tạo một build run `FAILED` để ghi nhận lỗi.

Các DataVersion là độc lập: một batch lỗi không chặn batch `ACTIVE` hợp lệ khác trong cùng invocation. Tuy nhiên, nếu có ít nhất một batch lỗi, API Job trả trạng thái lỗi để scheduler có thể retry batch còn `ACTIVE`.

Một payload có `data=[]` là không có kỳ parseable; batch đó không được `ACTIVATED`. Đây là hành vi chủ ý, tránh đánh dấu đã xử lý khi provider trả empty data.

## Dispatch và cấu hình Job

`IngestionJobService` dispatch workflow đặc biệt bằng `ingestion_jobs.code`:

```text
FINANCIAL_STATEMENT_BUILD
  → FinancialStatementBuildService
```

`parameters` mang ý nghĩa mô tả workflow:

```json
{
  "workflow": "FINANCIAL_STATEMENT_BUILD",
  "parameters": {}
}
```

Nó cố ý không dùng `operation=FETCH_URL`: đây không phải fetch job.

`ingestion_jobs.data_source_id` là `NOT NULL`. Project chưa có datasource riêng cho internal workflow, nên migration/catalog reuse active `PYTHON_GATEWAY` làm metadata/job owner theo convention hiện tại. Giá trị này **không** khiến `FINANCIAL_STATEMENT_BUILD` gọi Python: dispatcher dựa vào `code` và gọi `FinancialStatementBuildService` hoàn toàn trong Java.

Migration thủ công [V20260917__financial_statement_build.sql](../db/manual/V20260917__financial_statement_build.sql) yêu cầu đúng một datasource active `PYTHON_GATEWAY`, reuse retry/timeout convention hiện có, upsert duy nhất Job này, rồi verify lại datasource, dataset type, cron và parameters trước `COMMIT`.

Cron hiện dùng convention Spring sáu trường:

```text
0 */15 * * * *
```

Scheduler vẫn opt-in theo cấu hình `financial.ingestion.scheduler.enabled`.

## Code liên quan

| Trách nhiệm | Thành phần |
| --- | --- |
| Dispatch Job | `service/ingestion/IngestionJobService` |
| Chọn version, load raw, parse | `service/financial/FinancialStatementBuildService` |
| Transaction ghi period/statement/item và activate version | `service/financial/FinancialStatementBuildPersistenceService` |
| JPA repositories | `repository/jpa/financial/*` |
| Entity | `FinancialPeriodEntity`, `FinancialStatementEntity`, `FinancialStatementItemEntity` |
| Unit test parser | `FinancialStatementBuildServiceTests` |

## Kiểm chứng development đã thực hiện

Sau migration, job thực tế là:

```text
FINANCIAL_STATEMENT_BUILD
datasource     = PYTHON_GATEWAY (id 10)
dataset_type   = FINANCIAL_STATEMENT
cron           = 0 */15 * * * *
parameters     = {"workflow":"FINANCIAL_STATEMENT_BUILD","parameters":{}}
```

E2E với DataVersion ACTIVE thực tế:

- Ba batch VNDIRECT cho FPT (balance sheet, cash flow, income statement) đã tạo 1 period `2026/Q2`, 3 statements, 209 items và chuyển sang `ACTIVATED`.
- Hai batch VNSTOCK có `data=[]` vẫn giữ `ACTIVE`; không có statement/item partial được tạo từ hai batch đó.
- `mvn test -q` pass với JDK 21.

## Retry và idempotency

- Chỉ version `ACTIVE` được query; version đã `ACTIVATED` không thể được Job chọn lại.
- FinancialPeriod dùng unique/business key `(fiscal_year, period_type, end_date)`; ba statement VNDIRECT FPT `2026/Q2` đã reuse một `financial_period_id` thực tế.
- Trước khi insert statement, Job lookup theo `(raw_payload_id, financial_period_id, statement_type)`. Một retry sau rollback không có row partial để duplicate; một version đã thành công không còn `ACTIVE`.
- Test parser xác nhận empty data, VNStock period-key transpose, duplicate `item_id`, row-order deterministic và failure lifecycle.
