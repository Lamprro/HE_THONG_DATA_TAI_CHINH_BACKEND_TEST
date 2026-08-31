package com.hethongdata.taichinh.service.master;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.hethongdata.taichinh.common.AppParams;
import com.hethongdata.taichinh.entity.master.SecurityEntity;
import com.hethongdata.taichinh.repository.DataSourceRepository;
import com.hethongdata.taichinh.repository.IngestionJobRepository;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Converts one active tradable security into the complete Phase-1 raw collection plan.
 * Job codes are deterministic, so create, update, and reconciliation are idempotent.
 */
@Service
public class SecurityJobProvisioningService {
    private static final String EVERY_15_MINUTES = "0 */15 * * * *";
    private static final String EVERY_30_MINUTES = "0 */30 * * * *";
    private static final String WEEKDAY_AFTER_MARKET_CLOSE_UTC = "0 15 9 * * MON-FRI";
    private static final String DAILY_UTC = "0 0 18 * * *";
    private static final String WEEKLY_UTC = "0 0 2 * * SUN";

    private final DataSourceRepository dataSources;
    private final IngestionJobRepository ingestionJobs;
    private final ObjectMapper objectMapper;

    public SecurityJobProvisioningService(DataSourceRepository dataSources, IngestionJobRepository ingestionJobs,
                                          ObjectMapper objectMapper) {
        this.dataSources = dataSources;
        this.ingestionJobs = ingestionJobs;
        this.objectMapper = objectMapper;
    }

    @Transactional
    public int provision(SecurityEntity security) {
        ensureSources();
        List<JobDefinition> definitions = definitions(security);
        if (!Boolean.TRUE.equals(security.getIsActive())) {
            return ingestionJobs.deactivateByCodes(definitions.stream().map(JobDefinition::code).toList());
        }
        definitions.forEach(definition -> ingestionJobs.upsert(definition.sourceCode(), definition.code(), definition.name(),
                definition.datasetType(), definition.cron(), definition.parameters(), AppParams.DEFAULT_MAX_RETRIES,
                AppParams.DEFAULT_INGESTION_TIMEOUT_SECONDS, true));
        return definitions.size();
    }

    private void ensureSources() {
        dataSources.upsert("VNSTOCK", "VnStock", "API", null, "vnstock", false, "UNKNOWN", true);
        dataSources.upsert("VNDIRECT", "VNDirect", "API", null, "vndirect", false, "UNKNOWN", true);
        dataSources.upsert("CAFEF", "CafeF", "API", null, "cafef", false, "UNKNOWN", true);
    }

    private List<JobDefinition> definitions(SecurityEntity security) {
        String symbol = security.getSymbol();
        List<JobDefinition> jobs = new ArrayList<>();
        equityJobs(jobs, security, "VNSTOCK", "vnstock", true, false);
        equityJobs(jobs, security, "VNDIRECT", "vndirect", true, true);
        equityJobs(jobs, security, "CAFEF", "cafef", false, false);
        jobs.add(job(security, "CAFEF", "MANAGEMENT", "COMPANY", WEEKLY_UTC, "MANAGEMENT", "cafef", Map.of()));
        jobs.add(job(security, "CAFEF", "SUBSIDIARIES", "COMPANY", WEEKLY_UTC, "SUBSIDIARIES", "cafef", Map.of()));
        jobs.add(job(security, "CAFEF", "NEWS", "NEWS", EVERY_30_MINUTES, "NEWS", "cafef", Map.of("limit", "100")));
        jobs.add(job(security, "CAFEF", "EVENTS", "NEWS", DAILY_UTC, "EVENTS", "cafef", Map.of("limit", "100")));
        return List.copyOf(jobs);
    }

    private void equityJobs(List<JobDefinition> jobs, SecurityEntity security, String source, String provider,
                            boolean ratios, boolean vndirect) {
        jobs.add(job(security, source, "QUOTE", "MARKET_PRICE", EVERY_15_MINUTES, "QUOTE", provider, Map.of()));
        jobs.add(job(security, source, "OHLCV", "MARKET_PRICE", WEEKDAY_AFTER_MARKET_CLOSE_UTC, "OHLCV", provider,
                Map.of("lookbackDays", "7")));
        jobs.add(job(security, source, "COMPANY", "COMPANY", DAILY_UTC, "COMPANY", provider, Map.of()));
        for (String statement : List.of("balance_sheet", "income_statement", "cash_flow")) {
            Map<String, String> options = vndirect ? Map.of("statement", statement, "report_type", "QUARTER")
                    : provider.equals("cafef") ? Map.of("statement", statement, "period", "1")
                    : Map.of("statement", statement, "period", "quarter");
            jobs.add(job(security, source, statement.toUpperCase(), "FINANCIAL_STATEMENT", WEEKLY_UTC,
                    "FINANCIAL_STATEMENT", provider, options));
        }
        if (ratios) {
            jobs.add(job(security, source, "RATIO", "FINANCIAL_METRIC", WEEKLY_UTC, "RATIO", provider,
                    vndirect ? Map.of() : Map.of("period", "quarter")));
        }
    }

    private JobDefinition job(SecurityEntity security, String source, String suffix, String dataset, String cron,
                              String operation, String provider, Map<String, String> options) {
        ObjectNode root = objectMapper.createObjectNode();
        root.put("operation", operation);
        root.put("provider", provider);
        root.put("symbol", security.getSymbol());
        root.put("securityId", security.getId().toString());
        if ("OHLCV".equals(operation)) {
            root.put("lookbackDays", Integer.parseInt(options.get("lookbackDays")));
        }
        ObjectNode parameters = root.putObject("parameters");
        options.entrySet().stream().filter(entry -> !"lookbackDays".equals(entry.getKey()))
                .forEach(entry -> parameters.put(entry.getKey(), entry.getValue()));
        String code = source + "_" + security.getSymbol() + "_" + suffix
                + ("QUOTE".equals(suffix) ? "_15M" : "OHLCV".equals(suffix) ? "_DAILY"
                : "NEWS".equals(suffix) ? "_30M" : "EVENTS".equals(suffix) || "COMPANY".equals(suffix) ? "_DAILY" : "_WEEKLY");
        return new JobDefinition(code, source + " " + security.getSymbol() + " " + suffix.toLowerCase(), source,
                dataset, cron, root);
    }

    private record JobDefinition(String code, String name, String sourceCode, String datasetType, String cron,
                                 ObjectNode parameters) { }
}
