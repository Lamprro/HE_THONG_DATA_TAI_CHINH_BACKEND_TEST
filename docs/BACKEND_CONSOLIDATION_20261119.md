# Tinh gọn lớp backend — 19/11/2026 (nhánh `refactor/backend-consolidation`)

Nhánh tạo từ `feature/local-ai-research-preview` (đã gồm `refactor/admin-cleanup`). Chỉ commit local, chưa push.
Mục tiêu: bỏ lớp thừa / chia quá nhỏ, gộp theo nghiệp vụ, **không đổi luồng**. Những chỗ gộp sẽ đổi
ranh giới transaction hoặc hành vi được giữ nguyên và liệt kê ở cuối để xử lý sau.

Kết quả: `src/main/java` từ **234 → 177 file**. Không đổi URL, tham số, mã trạng thái, tên trường JSON,
schema DB, SQL, mã job/prompt, cờ `@ConditionalOnProperty`/`@Profile`, thuộc tính `@Transactional`.

## Đã gộp / xóa

### Cấu hình, khởi động, scheduler, controller admin, DTO
- 5 seeder (`IngestionJobCatalogSeeder`, `LlmPromptCatalogSeeder`, `MasterDataCatalogSeeder`, `RetryBudgetInitializer`,
  `ValidationRuleCatalogSeeder`) → `bootstrap/CatalogSeedConfiguration`: mỗi runner là `@Bean` có điều kiện riêng,
  giữ tên bean và thứ tự chạy cũ (`@Order(1..5)`).
- `PersistenceConfiguration` + `SchedulingConfiguration` + `FinancialDocumentStorageConfiguration` → `config/ApplicationConfiguration`
  (`@EntityScan("…entity")`, `@EnableJpaRepositories("…repository.jpa")` — đúng các package cũ).
- `CloudinaryDocumentConfiguration` (rỗng) → `@EnableConfigurationProperties` đặt trên `CloudinaryDocumentStorage`.
- `scheduler/{ingestion,llm,macro,master,validation}` (mỗi package 1 lớp) → một package `scheduler`.
- `IngestionController` + `IngestionJobController` + `RawPayloadController` + `source/DataSourceController` → `controller/ingestion/IngestionController`.
- `CompanyController` + `SecurityController` + `SecurityJobReconciliationController` → `controller/master/MasterDataController`.
- `AdminPromptController` → `LlmAdminController` (request record đổi tên `PromptVersionRequest`/`PromptEnableRequest`).
- 7 DTO Lombok `dto/master` → records trong `MasterDataDtos`; 2 DTO `dto/source` → `DataSourceDtos`.
  Thêm `AdminDtoJsonContractTests` khóa tên trường JSON, giá trị mặc định và ràng buộc validation.

### API user (`/api/v1`)
- Controller 7 → 5: `UserNewsController` + `UserLocalAiController` + `UserLocalAiPreviewController` → `UserAnalysisController`
  (giữ `@EndpointId` = operationId cũ; OpenAPI 37 path giữ nguyên từng byte, snapshot thêm route preview vốn đã chạy).
- Service 14 → 6:
  - `UserSecurityService` ← + `UserOverviewService`, phần `status` của `UserDataStatusService`.
  - `UserFinancialService` ← + `UserFinancialViewService`, `UserComparisonService`, `periods`/`lineage` của
    `UserDataStatusService`, `UserProvenanceService.metric` (→ `metricLineage`).
  - `UserMarketService` ← + `UserSupplementaryService`, `UserDataStatusService.filters`, `UserProvenanceService.price` (→ `priceLineage`).
  - `UserNewsService` ← + `UserAnalysisService` (`news(id)` → `analysis(id)`, `forecasts`).
  - `UserLocalAiService` ← + `UserLocalAiPreviewService` (cờ preview mặc định tắt, 404 khi tắt, whitelist giữ nguyên).
  - `UserReadSupport` nhận thêm `resolve(symbol, exchange)` và SQL trường security (tránh vòng phụ thuộc bean).
  - Timeout 30 s (overview/availability) và 20 s (analysis/forecasts) giữ bằng `@Transactional` cấp method.

### Entity, repository, ingestion, market, validation
- `MarketIndexReadService` + `MarketPriceReadService` → `MarketDataReadService` (`securityPrices`, `indexPrices`).
- `MarketIndexCatalogService` → `IndexJobProvisioningService.seedIndices()` (cùng transaction `seed()`).
- `IngestionReadService` → các method đọc trong `IngestionService` (`ingest()` vẫn không transaction).
- Xóa wrapper `repository/ingestion/RawPayloadRepository` (logic dựng payload vào `IngestionCompletionService.persistSuccess`)
  và `repository/master/MasterDataRepository` (chỉ chuyển tiếp; service dùng thẳng JPA repository).
- Xóa `FinancialMetricEntity` + `FinancialMetricJpaRepository` (không dùng; `financial_metrics` truy cập bằng JDBC).
  Lưu ý: `ddl-auto=validate` không còn kiểm bảng này.
- `SelectedDataVersion` → `DataVersionLifecycleService.requireActive(...)`; `entity/enums/IngestionRunStatus` → `IngestionRunEntity.Status` (giá trị lưu giữ nguyên).

### Financial, forecast, LLM, news
- `FinancialMetricCalculateService` + `FinancialMetricCatalogService` → `FinancialMetricWorkflowService` (`executeCalculate`, `seedCatalog`).
- `ProviderRatioParser` → static `FinancialMetricBuildPersistenceService.parseRatios`; `FinancialDocumentJson` → `OfficialFinancialSources.copyForStorage`.
- `FinancialValueBindingService` + `FinancialReconciliationEvidenceService` → `FinancialDataQualityService`.
- Xóa `FinancialMetricPayloadParser` (không có caller trong main; 3 assertion của test riêng bị xóa theo).
- `ForecastHorizon`, `ForecastMacroCoverageService`, `MarketScenarioCalculator`, `ForecastMarketEvidence` → `ForecastContextService`.
- `LlmSchemaValidator` → `LlmJson.validateSchema`; `LlmAttemptAudit` → `LlmRunStore.appendAttempt/readAttempts`;
  `NewsLlmValidator` → `LlmValidationService.newsResponseErrors/executeNewsRule`.
- `NewsUrlNormalizer` + `NewsContentClassification` → static trên `NewsWorkflowService`;
  `NewsDocumentReader` → `NewsRecoveryService.inspectDocument`; `NewsRecoveryValidation` → `NewsRecoveryStore`.

## Cố ý giữ nguyên (gộp sẽ đổi luồng — để chủ dự án quyết)
- Cặp Workflow/Persistence: `MarketPrice*`, `MarketIndex*`, `SupplementaryData*`, `Macro*`, `NewsWorkflow*`,
  `NewsRecoveryService/Store`, `FinancialForecastService/ForecastRunStore`, `FinancialMetricWorkflow/BuildPersistence`,
  `HistoricalFinancialMetricService`: mỗi version/batch/mã commit riêng qua proxy sau lời gọi chậm; gộp sẽ thành gọi nội bộ.
- `IngestionCompletionService` (transaction sau fetch ngoài), `DataVersionLifecycleService.rejectBuildFailure` (`REQUIRES_NEW`).
- `AdminOperationAuditConfiguration`/`ProductionConfiguration`: đường bảo mật/audit và thời điểm kiểm tra profile production.
- `AdminCommandService` với `AdminPipelineService`/`AdminAiActionService`, `AdminScheduleService` (vòng đời worker).
- `ChecksumService` (≈20 nơi dùng, có `new ChecksumService()`): nên đổi thành util tĩnh trong một đợt riêng.
- DTO ingestion (≈25 nơi dùng), payload parser dùng chung với validation/forecast, `LlmGateway` (test mock), `NewsLlmContext` (vòng phụ thuộc).
- Gộp toàn bộ scheduler thành 1 bean (mất cờ bật/tắt riêng từng scheduler).

## Phát hiện cần chủ dự án xem
- `ForecastContextService.targetDate(asOf, horizon)` chỉ test dùng và cho kết quả **khác** `endOfFutureQuarter` mà production dùng
  (asOf 29/02/2024, horizon 1: 31/03 vs 30/06). Code chết kèm test mâu thuẫn — chưa xóa.
- `FinancialDataQualityService.saveReconciliationEvidence` không có controller/service nào gọi.
- `MarketBackfillIsolatedJpaIntegrationTests` vốn hỏng từ trước khi bật DB test (context `WebEnvironment.NONE` thiếu
  `RequestMappingHandlerMapping` cho `AdminConsoleController`): đã đổi test sang `MOCK`.
  `UserApiIsolatedIntegrationTests` kiểm `doesNotContain("999")` trên chuỗi chứa UUID ngẫu nhiên → lỗi ngẫu nhiên; đã kiểm `value=999`.
- 2 lỗi có sẵn ở `NewsPipelineIntegrationFlowTests` (403 → URL_ONLY → SKIP theo P08) vẫn còn.
- Tài liệu cũ còn nhắc tên lớp đã gộp (chỉ là lịch sử, chưa sửa nội dung): `BAO_CAO_LUONG_NEWS_LLM.md`, `LLM_FLOW_DEV_BA.md`,
  `LLM_NEWS_PIPELINE.md`, `NEWS_LLM_CODE_DIAGRAMS.md`, `PRODUCTION_DATA_FLOW_REVIEW.md`, `FINANCIAL_METADATA_NEWS_MACRO_20261012.md`,
  `LLM_ATTEMPTS_MERGE.md`, `NEWS_RECOVERY_FLOW_AND_TESTS.md`, `STOCK_PRICE_SCENARIOS.md`, `CONTROLLER_CLEANUP_20261026.md`,
  `LOCAL_AI_RESEARCH_PREVIEW_20261119.md`. Bảng tra tên cũ → mới là mục "Đã gộp / xóa" ở trên.
