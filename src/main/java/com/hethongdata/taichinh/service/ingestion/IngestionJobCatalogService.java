package com.hethongdata.taichinh.service.ingestion;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.hethongdata.taichinh.common.AppParams;
import com.hethongdata.taichinh.repository.DataSourceRepository;
import com.hethongdata.taichinh.repository.IngestionJobRepository;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Versioned, idempotent catalog of Phase 1 endpoint jobs.
 * It seeds a small FPT scope only; a full-market universe is a later backfill decision.
 */
@Service
public class IngestionJobCatalogService {
    private static final String EVERY_15_MINUTES = "0 */15 * * * *";
    private static final String EVERY_30_MINUTES = "0 */30 * * * *";
    private static final String WEEKDAY_AFTER_MARKET_CLOSE_UTC = "0 15 9 * * MON-FRI";
    private static final String DAILY_UTC = "0 0 18 * * *";
    private static final String WEEKLY_UTC = "0 0 2 * * SUN";

    private final DataSourceRepository dataSources;
    private final IngestionJobRepository ingestionJobs;
    private final ObjectMapper objectMapper;

    public IngestionJobCatalogService(
            DataSourceRepository dataSources, IngestionJobRepository ingestionJobs, ObjectMapper objectMapper) {
        this.dataSources = dataSources;
        this.ingestionJobs = ingestionJobs;
        this.objectMapper = objectMapper;
    }

    @Transactional
    public int seed() {
        seedSources();
        List<JobDefinition> definitions = definitions();
        definitions.forEach(this::upsert);
        return definitions.size();
    }

    private void seedSources() {
        // Existing sources are updated by code; absent adapter providers are inserted once.
        source("PYTHON_GATEWAY", "Financial data gateway", "gateway");
        source("VNSTOCK", "VnStock", "vnstock");
        source("VNDIRECT", "VNDirect", "vndirect");
        source("CAFEF", "CafeF", "cafef");
        source("VNSTOCK_NEWS", "VnStock News", "vnstock-news");
    }

    private void source(String code, String name, String provider) {
        dataSources.upsert(code, name, "API", null, provider, false, "UNKNOWN", true);
    }

    private void upsert(JobDefinition definition) {
        ingestionJobs.upsert(definition.sourceCode(), definition.code(), definition.name(),
                definition.datasetType(), definition.cronExpression(), definition.parameters(),
                AppParams.DEFAULT_MAX_RETRIES, AppParams.DEFAULT_INGESTION_TIMEOUT_SECONDS, true);
    }

    private List<JobDefinition> definitions() {
        List<JobDefinition> jobs = new ArrayList<>();
        jobs.add(job("SYS_GATEWAY_HEALTH_15M", "Gateway health check", "PYTHON_GATEWAY", "OTHER",
                EVERY_15_MINUTES, "HEALTH", null, null, null, Map.of()));
        jobs.add(job("SYS_GATEWAY_PROVIDERS_DAILY", "Gateway provider registry", "PYTHON_GATEWAY", "OTHER",
                DAILY_UTC, "PROVIDERS", null, null, null, Map.of()));
        jobs.add(job("SYS_PROXY_PROVIDERS_DAILY", "Proxy provider registry", "PYTHON_GATEWAY", "OTHER",
                DAILY_UTC, "PROXY_PROVIDERS", null, null, null, Map.of()));

        equityJobs(jobs, "VNSTOCK", "vnstock", true, false);
        equityJobs(jobs, "VNDIRECT", "vndirect", true, true);
        cafeFJobs(jobs);

        jobs.add(job("VNSTOCK_NEWS_STATUS_DAILY", "VnStock News package status", "VNSTOCK_NEWS", "NEWS",
                DAILY_UTC, "NEWS_STATUS", null, null, null, Map.of()));
        jobs.add(job("VNSTOCK_NEWS_SITES_DAILY", "VnStock News sites", "VNSTOCK_NEWS", "NEWS",
                DAILY_UTC, "NEWS_SITES", null, null, null, Map.of()));
        jobs.add(job("VNSTOCK_NEWS_FPT_30M", "VnStock News for FPT", "VNSTOCK_NEWS", "NEWS",
                EVERY_30_MINUTES, "NEWS_COMPANY", null, "FPT", null, Map.of("limit", "50")));
        return List.copyOf(jobs);
    }

    private void equityJobs(List<JobDefinition> jobs, String source, String provider, boolean ratios, boolean vndirect) {
        String prefix = source + "_FPT";
        jobs.add(job(prefix + "_QUOTE_15M", source + " FPT quote", source, "MARKET_PRICE",
                EVERY_15_MINUTES, "QUOTE", provider, "FPT", null, Map.of()));
        jobs.add(job(prefix + "_OHLCV_DAILY", source + " FPT OHLCV trailing window", source, "MARKET_PRICE",
                WEEKDAY_AFTER_MARKET_CLOSE_UTC, "OHLCV", provider, "FPT", 7, Map.of()));
        jobs.add(job(prefix + "_COMPANY_DAILY", source + " FPT company profile", source, "COMPANY",
                DAILY_UTC, "COMPANY", provider, "FPT", null, Map.of()));
        for (String statement : List.of("balance_sheet", "income_statement", "cash_flow")) {
            Map<String, String> parameters = vndirect
                    ? Map.of("statement", statement, "report_type", "QUARTER")
                    : provider.equals("cafef")
                            ? Map.of("statement", statement, "period", "1")
                            : Map.of("statement", statement, "period", "quarter");
            jobs.add(job(prefix + "_" + statement.toUpperCase() + "_WEEKLY", source + " FPT " + statement,
                    source, "FINANCIAL_STATEMENT", WEEKLY_UTC, "FINANCIAL_STATEMENT", provider, "FPT", null, parameters));
        }
        if (ratios) {
            Map<String, String> parameters = vndirect ? Map.of() : Map.of("period", "quarter");
            jobs.add(job(prefix + "_RATIO_WEEKLY", source + " FPT ratios", source, "FINANCIAL_METRIC",
                    WEEKLY_UTC, "RATIO", provider, "FPT", null, parameters));
        }
    }

    private void cafeFJobs(List<JobDefinition> jobs) {
        equityJobs(jobs, "CAFEF", "cafef", false, false);
        for (String operation : List.of("MANAGEMENT", "SUBSIDIARIES")) {
            jobs.add(job("CAFEF_FPT_" + operation + "_WEEKLY", "CafeF FPT " + operation.toLowerCase(), "CAFEF",
                    "COMPANY", WEEKLY_UTC, operation, "cafef", "FPT", null, Map.of()));
        }
        jobs.add(job("CAFEF_FPT_NEWS_30M", "CafeF FPT news", "CAFEF", "NEWS", EVERY_30_MINUTES,
                "NEWS", "cafef", "FPT", null, Map.of("limit", "100")));
        jobs.add(job("CAFEF_FPT_EVENTS_DAILY", "CafeF FPT events", "CAFEF", "NEWS", DAILY_UTC,
                "EVENTS", "cafef", "FPT", null, Map.of("limit", "100")));
    }

    private JobDefinition job(
            String code, String name, String source, String dataset, String cron, String operation,
            String provider, String symbol, Integer lookbackDays, Map<String, String> parameters) {
        ObjectNode root = objectMapper.createObjectNode().put("operation", operation);
        if (provider != null) root.put("provider", provider);
        if (symbol != null) root.put("symbol", symbol);
        if (lookbackDays != null) root.put("lookbackDays", lookbackDays);
        ObjectNode nested = root.putObject("parameters");
        parameters.forEach(nested::put);
        return new JobDefinition(code, name, source, dataset, cron, root);
    }

    private record JobDefinition(
            String code, String name, String sourceCode, String datasetType,
            String cronExpression, ObjectNode parameters) {
    }
}
