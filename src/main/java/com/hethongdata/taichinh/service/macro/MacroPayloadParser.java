package com.hethongdata.taichinh.service.macro;

import com.fasterxml.jackson.databind.JsonNode;

import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.net.URI;
import java.time.*;
import java.util.*;

/** Strict contract boundary shared by raw validation and materialization. */
@Component
public class MacroPayloadParser {
    private static final Map<String, String> INDICATORS =
            Map.ofEntries(
                    Map.entry("VN_GDP_CURRENT_USD", "NY.GDP.MKTP.CD"),
                    Map.entry("VN_GDP_REAL_GROWTH_YOY", "NY.GDP.MKTP.KD.ZG"),
                    Map.entry("VN_CPI_INDEX_2010", "FP.CPI.TOTL"),
                    Map.entry("VN_CPI_INFLATION_YOY", "FP.CPI.TOTL.ZG"),
                    Map.entry("VN_OFFICIAL_USD_VND_YEAR_AVG", "PA.NUS.FCRF"),
                    Map.entry("VN_LENDING_RATE_YEAR", "FR.INR.LEND"),
                    Map.entry("VN_DEPOSIT_RATE_YEAR", "FR.INR.DPST"),
                    Map.entry("VN_UNEMPLOYMENT_ILO_ESTIMATE", "SL.UEM.TOTL.ZS"),
                    Map.entry("VN_EXPORTS_GOODS_SERVICES_USD", "NE.EXP.GNFS.CD"),
                    Map.entry("VN_IMPORTS_GOODS_SERVICES_USD", "NE.IMP.GNFS.CD"),
                    Map.entry("VN_USD_VND_QUARTER_AVG", "BIS:WS_XRU(1.0)/Q.VN.VND.A"));
    private static final Map<String, String> UNITS =
            Map.ofEntries(
                    Map.entry("VN_GDP_CURRENT_USD", "USD"),
                    Map.entry("VN_GDP_REAL_GROWTH_YOY", "%"),
                    Map.entry("VN_CPI_INDEX_2010", "INDEX_2010_100"),
                    Map.entry("VN_CPI_INFLATION_YOY", "%"),
                    Map.entry("VN_OFFICIAL_USD_VND_YEAR_AVG", "VND_PER_USD"),
                    Map.entry("VN_LENDING_RATE_YEAR", "%"),
                    Map.entry("VN_DEPOSIT_RATE_YEAR", "%"),
                    Map.entry("VN_UNEMPLOYMENT_ILO_ESTIMATE", "%"),
                    Map.entry("VN_EXPORTS_GOODS_SERVICES_USD", "USD"),
                    Map.entry("VN_IMPORTS_GOODS_SERVICES_USD", "USD"),
                    Map.entry("VN_USD_VND_QUARTER_AVG", "VND_PER_USD"));

    public Batch parse(JsonNode root, String expectedProvider) {
        require(root != null && root.isObject(), "Macro payload must be an object");
        require(
                "macro_observations.v1".equals(text(root, "schema_version")),
                "Unsupported macro schema");
        require(
                "MACRO".equals(text(root, "dataset")) && "VNM".equals(text(root, "country_code")),
                "Only Vietnam macro is supported");
        String provider = text(root, "provider");
        require(
                Set.of("worldbank", "bis").contains(provider) && provider.equals(expectedProvider),
                "Macro source provider mismatch");
        URI uri = URI.create(text(root, "source_url"));
        require(
                "https".equals(uri.getScheme())
                        && (provider.equals("bis") ? "stats.bis.org" : "api.worldbank.org")
                                .equals(uri.getHost()),
                "Invalid official macro source URL");
        Instant.parse(text(root, "retrieved_at"));
        LocalDate start = LocalDate.parse(text(root, "start_date")),
                end = LocalDate.parse(text(root, "end_date"));
        require(
                !start.isAfter(end) && !end.isAfter(LocalDate.now(ZoneId.of("Asia/Ho_Chi_Minh"))),
                "Invalid macro window");
        require(
                root.path("data").isArray()
                        && root.path("count").isIntegralNumber()
                        && root.path("count").asInt(-1) == root.path("data").size(),
                "Macro series count mismatch");
        Set<String> codes = new HashSet<>();
        List<Series> series = new ArrayList<>();
        int count = 0;
        for (JsonNode s : root.path("data")) {
            String code = text(s, "code"), frequency = text(s, "frequency"), unit = text(s, "unit");
            require(
                    codes.add(code) && unit.equals(UNITS.get(code)),
                    "Unknown/duplicate macro code or incorrect unit");
            require(
                    "VNM".equals(text(s, "country_code")) && provider.equals(text(s, "provider")),
                    "Series country/provider mismatch");
            require(
                    provider.equals("bis")
                            ? code.equals("VN_USD_VND_QUARTER_AVG") && frequency.equals("QUARTERLY")
                            : !code.equals("VN_USD_VND_QUARTER_AVG") && frequency.equals("YEARLY"),
                    "Native macro frequency mismatch");
            String name = text(s, "name"),
                    description = text(s, "description"),
                    indicator = text(s, "source_indicator");
            require(indicator.equals(INDICATORS.get(code)), "Source indicator/code mismatch");
            require(s.path("observations").isArray(), "Missing macro observations array");
            Set<LocalDate> seen = new HashSet<>();
            List<Observation> observations = new ArrayList<>();
            for (JsonNode o : s.path("observations")) {
                LocalDate date = LocalDate.parse(text(o, "observation_date"));
                String period = text(o, "period");
                require(
                        seen.add(date) && !date.isBefore(start) && !date.isAfter(end),
                        "Duplicate/out-of-window macro observation");
                if (frequency.equals("YEARLY"))
                    require(
                            date.equals(LocalDate.of(date.getYear(), 12, 31))
                                    && period.equals(Integer.toString(date.getYear())),
                            "Invalid annual period");
                else {
                    int quarter = (date.getMonthValue() - 1) / 3 + 1;
                    require(
                            date.equals(YearMonth.of(date.getYear(), quarter * 3).atEndOfMonth())
                                    && period.equals(date.getYear() + "-Q" + quarter),
                            "Invalid quarter period");
                }
                require(
                        o.path("value").isNumber() && o.path("source_record").isObject(),
                        "Missing numeric value/source evidence");
                BigDecimal value = o.path("value").decimalValue();
                JsonNode evidence = o.path("source_record");
                if (provider.equals("worldbank")) {
                    require(
                            indicator.equals(evidence.path("indicator").path("id").asText())
                                    && "VNM".equals(evidence.path("countryiso3code").asText())
                                    && period.equals(evidence.path("date").asText()),
                            "World Bank evidence does not match observation");
                    require(
                            evidence.path("value").isNumber()
                                    && evidence.path("value").decimalValue().compareTo(value) == 0,
                            "World Bank evidence value mismatch");
                } else {
                    require(
                            "Q".equals(evidence.path("FREQ").asText())
                                    && "VN".equals(evidence.path("REF_AREA").asText())
                                    && "VND".equals(evidence.path("CURRENCY").asText())
                                    && "A".equals(evidence.path("COLLECTION").asText())
                                    && "0".equals(evidence.path("UNIT_MULT").asText())
                                    && period.equals(evidence.path("TIME_PERIOD").asText()),
                            "BIS evidence does not match observation");
                    require(
                            new BigDecimal(text(evidence, "OBS_VALUE")).compareTo(value) == 0,
                            "BIS evidence value mismatch");
                }
                require(
                        value.precision() <= 38 && value.scale() <= 18,
                        "Macro value outside supported precision");
                if (unit.equals("USD")
                        || unit.equals("VND_PER_USD")
                        || unit.equals("INDEX_2010_100"))
                    require(value.signum() > 0, "Macro level must be positive");
                observations.add(new Observation(date, value));
                count++;
            }
            series.add(
                    new Series(
                            code,
                            name,
                            frequency,
                            unit,
                            description + " Source indicator: " + indicator,
                            List.copyOf(observations)));
        }
        require(
                count > 0
                        && root.path("observation_count").isIntegralNumber()
                        && count == root.path("observation_count").asInt(-1),
                "Macro observation count mismatch/empty batch");
        return new Batch(provider, List.copyOf(series), count);
    }

    private String text(JsonNode n, String key) {
        require(
                n.path(key).isTextual() && !n.path(key).asText().isBlank(),
                "Required macro field: " + key);
        return n.path(key).asText();
    }

    private void require(boolean condition, String message) {
        if (!condition) throw new IllegalArgumentException(message);
    }

    public record Observation(LocalDate date, BigDecimal value) {}

    public record Series(
            String code,
            String name,
            String frequency,
            String unit,
            String description,
            List<Observation> observations) {}

    public record Batch(String provider, List<Series> series, int count) {}
}
