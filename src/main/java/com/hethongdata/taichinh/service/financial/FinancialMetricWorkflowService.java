package com.hethongdata.taichinh.service.financial;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.hethongdata.taichinh.dto.ingestion.IngestionExecutionResponse;
import com.hethongdata.taichinh.entity.MetricDefinitionEntity;
import com.hethongdata.taichinh.entity.ingestion.IngestionJobEntity;
import com.hethongdata.taichinh.repository.ingestion.IngestionRunRepository;
import com.hethongdata.taichinh.repository.jpa.financial.MetricDefinitionJpaRepository;
import com.hethongdata.taichinh.repository.jpa.ingestion.IngestionRunJpaRepository;
import com.hethongdata.taichinh.repository.jpa.validation.DataVersionJpaRepository;
import com.hethongdata.taichinh.service.validation.DataVersionLifecycleService;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * FINANCIAL_METRIC job entry points and the reviewed metric catalog.
 * Job methods are not transactional: each batch/security commits through its own proxied
 * call ({@link FinancialMetricBuildPersistenceService#build}, {@link HistoricalFinancialMetricService#calculate}).
 */
@Service
public class FinancialMetricWorkflowService {
    public static final String WORKFLOW="FINANCIAL_METRIC_BUILD";
    public static final String CALCULATE_WORKFLOW = "FINANCIAL_METRIC_CALCULATE";
    private final DataVersionJpaRepository versions;
    private final IngestionRunRepository runs;
    private final FinancialMetricBuildPersistenceService writes;
    private final DataVersionLifecycleService lifecycle;
    private final JdbcTemplate db;
    private final HistoricalFinancialMetricService actualRatios;
    private final IngestionRunJpaRepository persistence;
    private final ObjectMapper mapper;
    private final MetricDefinitionJpaRepository definitions;
    public FinancialMetricWorkflowService(DataVersionJpaRepository versions, IngestionRunRepository runs,
            FinancialMetricBuildPersistenceService writes, DataVersionLifecycleService lifecycle, JdbcTemplate db,
            HistoricalFinancialMetricService actualRatios, IngestionRunJpaRepository persistence, ObjectMapper mapper,
            MetricDefinitionJpaRepository definitions) {
        this.versions=versions;this.runs=runs;this.writes=writes;this.lifecycle=lifecycle;
        this.db=db;this.actualRatios=actualRatios;this.persistence=persistence;this.mapper=mapper;
        this.definitions=definitions;
    }

    // ---- FINANCIAL_METRIC_BUILD: provider RATIO batches ----
    public boolean supports(String code) {return WORKFLOW.equals(code);}
    public IngestionExecutionResponse execute(IngestionJobEntity job,String trigger) {
        var candidates=versions.findByDataDomainAndStatusOrderByCreatedAtAsc("FINANCIAL_METRIC","ACTIVE");
        return executeCandidates(job,trigger,candidates);
    }
    public IngestionExecutionResponse executeVersion(IngestionJobEntity job,UUID id,String trigger) {
        if(!supports(job.getCode())) throw new IllegalArgumentException("Requires FINANCIAL_METRIC_BUILD job");
        return executeCandidates(job,trigger,java.util.List.of(DataVersionLifecycleService.requireActive(versions,id,"FINANCIAL_METRIC")));
    }
    private IngestionExecutionResponse executeCandidates(IngestionJobEntity job,String trigger,java.util.List<com.hethongdata.taichinh.entity.validation.DataVersionEntity> candidates) {
        UUID last=null;int rejected=0;
        if (candidates.isEmpty()) {
            var run=runs.startInternalBatch(job.getDataSource(),job,trigger,WORKFLOW);
            writes.noWork(run);last=run.getId();
        }
        for (var v:candidates) {
            var run=runs.startInternalBatch(job.getDataSource(),job,trigger,WORKFLOW);last=run.getId();
            try {writes.build(v.getId(),run);}
            catch (RuntimeException e) {
                runs.markFailed(run,"PROTOCOL",null,WORKFLOW+" failed for version "+v.getId());
                lifecycle.rejectBuildFailure(v.getId(),WORKFLOW,e);rejected++;
            }
        }
        return new IngestionExecutionResponse(last,null,rejected==0?"SUCCESS":"COMPLETED_WITH_REJECTIONS",200,"application/json",null,false);
    }

    // ---- FINANCIAL_METRIC_CALCULATE: job entry point for the same source-verified actual ratios used by the admin API ----
    public boolean supportsCalculate(String code) { return CALCULATE_WORKFLOW.equals(code); }
    public IngestionExecutionResponse executeCalculate(IngestionJobEntity job, String trigger) {
        var parameters = job.getParameters();
        String symbol = parameters.path("symbol").asText("").trim();
        LocalDate start = LocalDate.parse(parameters.path("startDate").asText("2016-01-01"));
        LocalDate end = LocalDate.parse(parameters.path("endDate").asText(
                LocalDate.now(ZoneId.of("Asia/Ho_Chi_Minh")).toString()));
        if (start.isAfter(end) || end.isAfter(LocalDate.now(ZoneId.of("Asia/Ho_Chi_Minh"))))
            throw new IllegalArgumentException("Choose a valid actual-data date range");
        var symbols = symbol.isEmpty()
                ? db.queryForList("SELECT DISTINCT s.symbol FROM securities s "
                        + "JOIN financial_statements f ON f.security_id=s.id "
                        + "WHERE f.is_current AND f.is_canonical ORDER BY s.symbol", String.class)
                : java.util.List.of(symbol);
        var run = runs.startInternalBatch(job.getDataSource(), job, trigger, CALCULATE_WORKFLOW);
        int statements = 0, inserted = 0, unchanged = 0;
        try {
            for (String selected : symbols) {
                var result = actualRatios.calculate(
                        new HistoricalFinancialMetricService.Request(selected, start, end));
                statements += result.sourceStatements(); inserted += result.inserted();
                unchanged += result.unchanged();
            }
            var audit = mapper.createObjectNode().put("workflow", CALCULATE_WORKFLOW)
                    .put("calculationVersion", "historical-actual-ratios-v1")
                    .put("securities", symbols.size()).put("sourceStatements", statements)
                    .put("inserted", inserted).put("unchanged", unchanged)
                    .put("startDate", start.toString()).put("endDate", end.toString());
            run.markWorkflowSuccess(audit, Instant.now(), inserted + unchanged, inserted, 0);
            persistence.save(run);
            return new IngestionExecutionResponse(run.getId(), null, "SUCCESS", 200,
                    "application/json", null, false);
        } catch (RuntimeException exception) {
            runs.markFailed(run, "PROTOCOL", null,
                    CALCULATE_WORKFLOW + " failed; earlier securities may have committed; replay is idempotent");
            throw exception;
        }
    }

    // ---- Reviewed metric catalog. Formula strings are descriptive only, never executed. ----
    @Transactional
    public int seedCatalog() {
        catalogDefinitions().forEach(definition -> definitions.findByCode(definition.code())
                .ifPresentOrElse(entity -> entity.refresh(definition.name(), definition.category(), definition.description(),
                        definition.formula(), definition.unit(), definition.higherIsBetter()),
                        () -> definitions.save(MetricDefinitionEntity.create(definition.code(), definition.name(),
                                definition.category(), definition.description(), definition.formula(), definition.unit(),
                                definition.higherIsBetter()))));
        return catalogDefinitions().size();
    }

    private static List<Definition> catalogDefinitions() {
        return List.of(
                d("PRICE_TO_EARNINGS", "Price to earnings", "VALUATION", "P/E provider metric", "Provider supplied", "x", false),
                d("PRICE_TO_BOOK", "Price to book", "VALUATION", "P/B provider metric", "Provider supplied", "x", false),
                d("EPS_TR", "EPS trailing", "PER_SHARE", "Trailing EPS provider metric", "Provider supplied", "VND", true),
                d("ROAE_TR_AVG5Q", "ROAE trailing average 5 quarters", "PROFITABILITY", "Provider trailing average metric", "Provider supplied", "%", true),
                d("ROAA_TR_AVG5Q", "ROAA trailing average 5 quarters", "PROFITABILITY", "Provider trailing average metric", "Provider supplied", "%", true),
                d("DIVIDEND_YIELD", "Dividend yield", "MARKET", "Provider dividend yield", "Provider supplied", "%", true),
                d("GROSS_MARGIN", "Gross margin", "PROFITABILITY", "Gross profit over net sales", "GROSS_PROFIT / NET_SALES * 100", "%", true),
                d("OPERATING_MARGIN", "Operating margin", "PROFITABILITY", "Operating profit over net sales", "NET_PROFIT_FROM_OPERATING_ACTIVITIES / NET_SALES * 100", "%", true),
                d("NET_MARGIN", "Net margin", "PROFITABILITY", "Net profit after tax over net sales", "NET_PROFIT_AFTER_TAX / NET_SALES * 100", "%", true),
                d("CURRENT_RATIO", "Current ratio", "LIQUIDITY", "Current assets over short term liabilities", "CURRENT_ASSETS / SHORT_TERM_LIABILITIES", "x", true),
                d("CASH_RATIO", "Cash ratio", "LIQUIDITY", "Cash and equivalents over short term liabilities", "CASH_AND_CASH_EQUIVALENTS / SHORT_TERM_LIABILITIES", "x", true),
                d("LIABILITIES_TO_EQUITY", "Liabilities to equity", "LEVERAGE", "Liabilities over owners equity", "LIABILITIES / OWNERS_EQUITY", "x", false),
                d("LIABILITIES_TO_ASSETS", "Liabilities to assets", "LEVERAGE", "Liabilities over total assets", "LIABILITIES / TOTAL_ASSETS", "x", false),
                d("EQUITY_TO_ASSETS", "Equity to assets", "LEVERAGE", "Owners equity over total assets", "OWNERS_EQUITY / TOTAL_ASSETS", "x", true),
                d("OPERATING_CASH_FLOW_MARGIN", "Operating cash flow margin", "QUALITY", "Operating cash flow over net sales", "NET_CASHFLOW_FROM_OPERATING_ACTIVITIES / NET_SALES * 100", "%", true),
                d("CASH_FLOW_TO_NET_PROFIT", "Cash flow to net profit", "QUALITY", "Operating cash flow over net profit", "NET_CASHFLOW_FROM_OPERATING_ACTIVITIES / NET_PROFIT_AFTER_TAX", "x", true));
    }
    private static Definition d(String code, String name, String category, String description, String formula, String unit, boolean higher) {
        return new Definition(code, name, category, description, formula, unit, higher);
    }
    private record Definition(String code, String name, String category, String description, String formula, String unit, boolean higherIsBetter) {}
}
