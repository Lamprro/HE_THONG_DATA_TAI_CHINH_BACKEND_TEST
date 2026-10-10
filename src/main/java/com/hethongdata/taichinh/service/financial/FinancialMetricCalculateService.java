package com.hethongdata.taichinh.service.financial;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.hethongdata.taichinh.dto.ingestion.IngestionExecutionResponse;
import com.hethongdata.taichinh.entity.ingestion.IngestionJobEntity;
import com.hethongdata.taichinh.repository.ingestion.IngestionRunRepository;
import com.hethongdata.taichinh.repository.jpa.ingestion.IngestionRunJpaRepository;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

/** Job entry point for the same source-verified actual ratios used by the admin API. */
@Service
public class FinancialMetricCalculateService {
    public static final String WORKFLOW = "FINANCIAL_METRIC_CALCULATE";
    private final JdbcTemplate db;
    private final HistoricalFinancialMetricService actualRatios;
    private final IngestionRunRepository runs;
    private final IngestionRunJpaRepository persistence;
    private final ObjectMapper mapper;
    public FinancialMetricCalculateService(JdbcTemplate db,
            HistoricalFinancialMetricService actualRatios, IngestionRunRepository runs,
            IngestionRunJpaRepository persistence, ObjectMapper mapper) {
        this.db = db; this.actualRatios = actualRatios; this.runs = runs; this.mapper = mapper;
        this.persistence = persistence;
    }
    public boolean supports(String code) { return WORKFLOW.equals(code); }
    public IngestionExecutionResponse execute(IngestionJobEntity job, String trigger) {
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
        var run = runs.startInternalBatch(job.getDataSource(), job, trigger, WORKFLOW);
        int statements = 0, inserted = 0, unchanged = 0;
        try {
            for (String selected : symbols) {
                var result = actualRatios.calculate(
                        new HistoricalFinancialMetricService.Request(selected, start, end));
                statements += result.sourceStatements(); inserted += result.inserted();
                unchanged += result.unchanged();
            }
            var audit = mapper.createObjectNode().put("workflow", WORKFLOW)
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
                    WORKFLOW + " failed; earlier securities may have committed; replay is idempotent");
            throw exception;
        }
    }
}
