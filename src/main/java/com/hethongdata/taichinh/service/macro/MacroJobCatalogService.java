package com.hethongdata.taichinh.service.macro;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.hethongdata.taichinh.common.AppParams;
import com.hethongdata.taichinh.entity.validation.ValidationRuleEntity;
import com.hethongdata.taichinh.repository.ingestion.*;
import com.hethongdata.taichinh.repository.jpa.validation.ValidationRuleJpaRepository;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.*;
import java.util.*;

/** Seeds only macro sources/jobs/rule; never refreshes unrelated NEWS or equity job definitions. */
@Service
public class MacroJobCatalogService {
    private final DataSourceRepository sources;
    private final IngestionJobRepository jobs;
    private final ValidationRuleJpaRepository rules;
    private final JdbcTemplate db;
    private final ObjectMapper mapper;

    public MacroJobCatalogService(
            DataSourceRepository sources,
            IngestionJobRepository jobs,
            ValidationRuleJpaRepository rules,
            JdbcTemplate db,
            ObjectMapper mapper) {
        this.sources = sources;
        this.jobs = jobs;
        this.rules = rules;
        this.db = db;
        this.mapper = mapper;
    }

    @Transactional
    public Map<String, Object> seed() {
        var dates =
                db.query(
                        "SELECT min(d) FROM (SELECT (price_timestamp AT TIME ZONE"
                            + " 'Asia/Ho_Chi_Minh')::date d FROM market_prices WHERE is_canonical"
                            + " AND interval_code='1d' UNION ALL SELECT p.start_date FROM"
                            + " financial_periods p JOIN financial_statements s ON"
                            + " s.financial_period_id=p.id WHERE s.is_current AND s.is_canonical)"
                            + " x",
                        (r, n) -> r.getDate(1) == null ? null : r.getDate(1).toLocalDate());
        LocalDate earliest = dates.isEmpty() ? null : dates.getFirst();
        if (earliest == null)
            earliest = LocalDate.now(ZoneId.of("Asia/Ho_Chi_Minh")).minusYears(10);
        LocalDate start = LocalDate.of(earliest.getYear(), 1, 1);
        for (String provider : List.of("worldbank", "bis")) {
            String source = provider.equals("bis") ? "BIS_MACRO" : "WORLD_BANK_MACRO";
            sources.upsert(
                    source,
                    provider.equals("bis") ? "BIS Vietnam macro" : "World Bank Vietnam macro",
                    "API",
                    null,
                    provider,
                    true,
                    "FREE",
                    true);
            var parameters =
                    mapper.createObjectNode()
                            .put("operation", "MACRO_OBSERVATIONS")
                            .put("provider", provider)
                            .put("startDate", start.toString());
            parameters.putObject("parameters").put("country", "VNM");
            String code =
                    provider.equals("bis")
                            ? "MACRO_VN_BIS_QUARTERLY"
                            : "MACRO_VN_WORLDBANK_QUARTERLY";
            // 08:00 Vietnam on day 15 after each completed quarter (UTC cron evaluation).
            jobs.upsert(
                    source,
                    code,
                    "Vietnam macro refresh: " + provider,
                    "MACRO",
                    "0 0 1 15 1,4,7,10 *",
                    parameters,
                    AppParams.DEFAULT_MAX_RETRIES,
                    120,
                    true);
        }
        var config =
                mapper.createObjectNode()
                        .put("schema", "macro_observations.v1")
                        .put("country", "VNM");
        rules.findByCode("MACRO_PAYLOAD_VALID")
                .ifPresentOrElse(
                        r ->
                                r.refresh(
                                        "Vietnam macro contract",
                                        "MACRO",
                                        "CRITICAL",
                                        "BUSINESS",
                                        config,
                                        "Requires official-source evidence, units, native periods,"
                                            + " count and duplicate-free observations",
                                        "MACRO_PAYLOAD_VALID"),
                        () ->
                                rules.save(
                                        ValidationRuleEntity.create(
                                                "MACRO_PAYLOAD_VALID",
                                                "Vietnam macro contract",
                                                "MACRO",
                                                "CRITICAL",
                                                "BUSINESS",
                                                config,
                                                "Requires official-source evidence, units, native"
                                                    + " periods, count and duplicate-free"
                                                    + " observations",
                                                "MACRO_PAYLOAD_VALID")));
        return Map.of(
                "jobs",
                2,
                "country",
                "VNM",
                "startDate",
                start,
                "refreshFrequency",
                "QUARTERLY",
                "sourceFrequencies",
                List.of("YEARLY", "QUARTERLY"));
    }
}
