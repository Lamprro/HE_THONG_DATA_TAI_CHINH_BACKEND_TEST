package com.hethongdata.taichinh;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

class LayeringRulesTests {
  // Allowlist: file còn được phép có SQL — xóa dần sau mỗi đợt, cuối cùng phải rỗng
  static final Set<String> ALLOW = Set.of(
    "controller/admin/LlmAdminController.java",
    "controller/admin/MarketDataAdminController.java",
    "config/AdminOperationAuditConfiguration.java",
    "service/llm/LlmRunStore.java",
    "service/news/recovery/NewsRecoveryStore.java",
    "service/forecast/ForecastRunStore.java",
    "service/financial/FinancialDataQualityService.java",
    "service/localai/LocalAiTrainerService.java",
    "service/admin/AdminConsoleReadService.java",
    "service/admin/AdminCommandService.java",
    "service/user/UserFinancialService.java",
    "service/user/UserMarketService.java",
    "service/llm/NewsLlmService.java",
    "service/localai/LocalAiSuiteService.java",
    "service/forecast/ForecastContextService.java",
    "service/financial/FinancialMetricBuildPersistenceService.java",
    "service/market/TrainingDataQualityService.java",
    "service/forecast/ForecastPromptService.java",
    "service/user/UserLocalAiService.java",
    "service/market/SupplementaryDataPersistenceService.java",
    "service/user/UserSecurityService.java",
    "service/admin/AdminScheduleService.java",
    "service/macro/MacroWorkflowPersistenceService.java",
    "service/llm/LlmPromptCatalog.java",
    "service/financial/HistoricalFinancialMetricService.java",
    "service/news/recovery/NewsRecoveryCatalog.java",
    "service/financial/FinancialDisclosureDocumentStore.java",
    "service/user/UserNewsService.java",
    "service/macro/MacroJobCatalogService.java",
    "service/admin/NativeJobGuard.java",
    "service/forecast/ForecastValidationService.java",
    "service/llm/LlmValidationService.java",
    "service/llm/NewsLlmContext.java",
    "service/user/UserReadSupport.java",
    "service/DatabaseHealthService.java",
    "service/financial/FinancialMetricWorkflowService.java",
    "service/market/MarketPriceWorkflowPersistenceService.java",
    "service/validation/DataVersionLifecycleService.java"
  );

  @Test
  void noSqlOutsideRepository() throws Exception {
    Path root = Path.of("src/main/java/com/hethongdata/taichinh");
    var bad = new ArrayList<String>();
    try (var files = Files.walk(root)) {
      for (Path p : files.filter(f -> f.toString().endsWith(".java")).toList()) {
        String rel = root.relativize(p).toString().replace('\\', '/');
        if (rel.startsWith("repository/") || ALLOW.contains(rel)) continue;
        String s = Files.readString(p);
        if (s.contains("JdbcTemplate") || s.matches("(?is).*\\b(select\\s+[\\w*(]|insert\\s+into|update\\s+\\w+\\s+set|delete\\s+from)\\b.*")) {
          bad.add(rel);
        }
      }
    }
    assertEquals(List.of(), bad, "SQL/JdbcTemplate ngoài repository");
  }
}
