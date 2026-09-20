package com.hethongdata.taichinh.service.financial;

import com.fasterxml.jackson.databind.JsonNode;
import com.hethongdata.taichinh.application.port.error.ExternalErrorCategory;
import com.hethongdata.taichinh.dto.ingestion.IngestionExecutionResponse;
import com.hethongdata.taichinh.entity.ingestion.IngestionJobEntity;
import com.hethongdata.taichinh.entity.ingestion.IngestionRunEntity;
import com.hethongdata.taichinh.entity.ingestion.RawPayloadEntity;
import com.hethongdata.taichinh.entity.validation.DataVersionEntity;
import com.hethongdata.taichinh.repository.ingestion.IngestionRunRepository;
import com.hethongdata.taichinh.repository.jpa.ingestion.RawPayloadJpaRepository;
import com.hethongdata.taichinh.repository.jpa.validation.DataVersionJpaRepository;
import com.hethongdata.taichinh.service.validation.DataVersionLifecycleService;

import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.LocalDate;
import java.time.Month;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Builds normalized financial statements only from validated FINANCIAL_STATEMENT batches. */
@Service
public class FinancialStatementBuildService {
    public static final String FINANCIAL_STATEMENT_BUILD = "FINANCIAL_STATEMENT_BUILD";
    private static final Pattern PERIOD_KEY = Pattern.compile("^(\\d{4})[-_ ]?(Q[1-4]|FY|TTM|M(?:[1-9]|1[0-2]))$", Pattern.CASE_INSENSITIVE);

    private final DataVersionJpaRepository versions;
    private final RawPayloadJpaRepository rawPayloads;
    private final IngestionRunRepository ingestionRuns;
    private final FinancialStatementBuildPersistenceService writes;
    private final DataVersionLifecycleService lifecycle;

    public FinancialStatementBuildService(
            DataVersionJpaRepository versions,
            RawPayloadJpaRepository rawPayloads,
            IngestionRunRepository ingestionRuns,
            FinancialStatementBuildPersistenceService writes,
            DataVersionLifecycleService lifecycle) {
        this.versions = versions;
        this.rawPayloads = rawPayloads;
        this.ingestionRuns = ingestionRuns;
        this.writes = writes;
        this.lifecycle = lifecycle;
    }

    public boolean supports(String code) {
        return FINANCIAL_STATEMENT_BUILD.equals(code);
    }

    public IngestionExecutionResponse execute(IngestionJobEntity job, String triggerType) {
        List<DataVersionEntity> candidates =
                versions.findByDataDomainAndStatusOrderByCreatedAtAsc("FINANCIAL_STATEMENT", "ACTIVE");
        if (candidates.isEmpty()) {
            IngestionRunEntity run =
                    ingestionRuns.startInternalBatch(job.getDataSource(), job, triggerType, FINANCIAL_STATEMENT_BUILD);
            writes.noWork(run);
            return new IngestionExecutionResponse(run.getId(), null, "SUCCESS", 200, "application/json", null, false);
        }

        UUID latestRunId = null;
        int rejected = 0;
        for (DataVersionEntity version : candidates) {
            IngestionRunEntity run =
                    ingestionRuns.startInternalBatch(job.getDataSource(), job, triggerType, FINANCIAL_STATEMENT_BUILD);
            latestRunId = run.getId();
            try {
                List<RawPayloadEntity> payloads =
                        rawPayloads.findByIngestionRunIdAndEntityTypeOrderByFetchedAtAsc(
                                version.getIngestionRunId(), "FINANCIAL_STATEMENT");
                if (payloads.isEmpty()) {
                    throw new IllegalStateException("FINANCIAL_STATEMENT version has no statement raw payloads: " + version.getId());
                }
                List<StatementDraft> drafts = new ArrayList<>();
                for (RawPayloadEntity payload : payloads) drafts.addAll(parse(payload));
                if (drafts.isEmpty()) {
                    throw new IllegalStateException("FINANCIAL_STATEMENT version has no parseable statement periods: " + version.getId());
                }
                writes.persist(version.getId(), run, drafts);
            } catch (RuntimeException exception) {
                ingestionRuns.markFailed(
                        run,
                        ExternalErrorCategory.PROTOCOL.name(),
                        null,
                        FINANCIAL_STATEMENT_BUILD + " failed for version " + version.getId());
                lifecycle.rejectBuildFailure(version.getId(), FINANCIAL_STATEMENT_BUILD, exception);
                rejected++;
            }
        }
        return new IngestionExecutionResponse(
                latestRunId,
                null,
                rejected == 0 ? "SUCCESS" : "COMPLETED_WITH_REJECTIONS",
                200,
                "application/json",
                null,
                false);
    }

    List<StatementDraft> parse(RawPayloadEntity payload) {
        JsonNode root = payload.getPayload();
        if (root == null || root.isNull()) throw new IllegalArgumentException("Statement raw payload has no JSON payload: " + payload.getId());
        String symbol = firstText(root, "symbol", "code");
        if (symbol == null) symbol = payload.getSourceSymbol();
        if (symbol == null || symbol.isBlank()) throw new IllegalArgumentException("Statement raw payload has no symbol: " + payload.getId());
        String statementType = statementType(firstText(root, "dataset", "statement_type", "statementType"));
        String reportScope = reportScope(firstText(root, "report_scope", "reportScope", "formType"));
        String provider = firstText(root, "provider");
        JsonNode data = root.path("data");
        if (data.isArray()) return arrayDrafts(payload, symbol, statementType, reportScope, provider, root, data);
        if (data.isObject()) return keyedDrafts(payload, symbol, statementType, reportScope, provider, data);
        throw new IllegalArgumentException("Statement raw payload has unsupported data shape: " + payload.getId());
    }

    private List<StatementDraft> arrayDrafts(
            RawPayloadEntity payload, String symbol, String statementType, String reportScope, String provider, JsonNode root, JsonNode data) {
        Map<Period, List<ItemDraft>> grouped = new LinkedHashMap<>();
        for (JsonNode entry : data) {
            List<PeriodValue> periodValues = rowPeriodValues(entry);
            if (!periodValues.isEmpty()) {
                for (PeriodValue periodValue : periodValues) {
                    grouped.computeIfAbsent(periodValue.period(), ignored -> new ArrayList<>())
                            .add(item(entry, periodValue.value(), provider));
                }
                continue;
            }
            Period period = period(entry, root);
            grouped.computeIfAbsent(period, ignored -> new ArrayList<>()).add(item(entry, null, provider));
        }
        return grouped.entrySet().stream()
                .map(entry -> new StatementDraft(payload, symbol, statementType, reportScope, entry.getKey(), uniqueItemCodes(entry.getValue())))
                .toList();
    }

    private List<StatementDraft> keyedDrafts(
            RawPayloadEntity payload, String symbol, String statementType, String reportScope, String provider, JsonNode data) {
        Map<Period, List<ItemDraft>> grouped = new LinkedHashMap<>();
        data.fields().forEachRemaining(
                item -> {
                    if (!item.getValue().isObject()) return;
                    item.getValue().fields().forEachRemaining(
                            value -> {
                                Period period = period(value.getKey());
                                BigDecimal number = decimal(value.getValue());
                                if (number == null) return;
                                grouped.computeIfAbsent(period, ignored -> new ArrayList<>())
                                        .add(new ItemDraft(code(item.getKey()), item.getKey(), number, value.getValue().asText(), null, item.getKey(), item.getKey(), provider));
                            });
                });
        return grouped.entrySet().stream()
                .map(entry -> new StatementDraft(payload, symbol, statementType, reportScope, entry.getKey(), uniqueItemCodes(entry.getValue())))
                .toList();
    }

    private List<PeriodValue> rowPeriodValues(JsonNode entry) {
        List<PeriodValue> values = new ArrayList<>();
        entry.fields().forEachRemaining(field -> {
            if (!PERIOD_KEY.matcher(field.getKey().trim()).matches()) return;
            BigDecimal value = decimal(field.getValue());
            if (value != null) values.add(new PeriodValue(period(field.getKey()), field.getValue()));
        });
        return values;
    }

    private ItemDraft item(JsonNode entry, JsonNode periodValue, String provider) {
        String sourceCode = firstText(entry, "item_id", "itemCode", "item_code", "code");
        String name = firstText(entry, "item", "itemEnName", "item_name", "name", "itemVnName");
        String itemCode = sourceCode != null && !sourceCode.matches("[0-9.]+") ? code(sourceCode) : code(name);
        if (itemCode == null || itemCode.isBlank()) throw new IllegalArgumentException("Statement item has no code/name");
        JsonNode rawValue = periodValue == null ? first(entry, "numericValue", "value", "amount") : periodValue;
        BigDecimal value = decimal(rawValue);
        if (value == null) throw new IllegalArgumentException("Statement item " + itemCode + " has no numeric value");
        Integer displayOrder = integer(first(entry, "displayOrder", "display_order"));
        return new ItemDraft(
                itemCode,
                name == null ? itemCode : name,
                value,
                rawValue.asText(),
                displayOrder,
                sourceCode == null ? itemCode : sourceCode,
                name == null ? itemCode : name,
                provider);
    }

    private Period period(JsonNode entry, JsonNode root) {
        String raw = firstText(entry, "fiscalDate", "fiscal_date", "period", "period_key");
        if (raw != null && raw.matches("\\d{4}-\\d{2}-\\d{2}")) {
            LocalDate end = LocalDate.parse(raw);
            String reportType = firstText(entry, "reportType", "report_type");
            if (reportType == null) reportType = firstText(root, "report_type", "reportType");
            return period(end, reportType);
        }
        if (raw == null) raw = firstText(root, "period", "period_key", "fiscal_period");
        if (raw == null) throw new IllegalArgumentException("Statement item has no fiscal date/period");
        return period(raw);
    }

    private Period period(String raw) {
        Matcher matcher = PERIOD_KEY.matcher(raw.trim().toUpperCase(Locale.ROOT));
        if (!matcher.matches()) throw new IllegalArgumentException("Unsupported fiscal period " + raw);
        int year = Integer.parseInt(matcher.group(1));
        String type = matcher.group(2).toUpperCase(Locale.ROOT);
        if (type.startsWith("Q")) {
            int quarter = Integer.parseInt(type.substring(1));
            Month startMonth = Month.of((quarter - 1) * 3 + 1);
            LocalDate start = LocalDate.of(year, startMonth, 1);
            return new Period(year, type, start, start.plusMonths(3).minusDays(1));
        }
        if ("FY".equals(type)) return new Period(year, type, LocalDate.of(year, 1, 1), LocalDate.of(year, 12, 31));
        throw new IllegalArgumentException("Fiscal period needs an explicit end date: " + raw);
    }

    private Period period(LocalDate end, String reportType) {
        String normalized = reportType == null ? "QUARTER" : reportType.toUpperCase(Locale.ROOT);
        if (normalized.contains("YEAR") || normalized.contains("ANNUAL")) {
            return new Period(end.getYear(), "FY", LocalDate.of(end.getYear(), 1, 1), end);
        }
        int quarter = (end.getMonthValue() - 1) / 3 + 1;
        LocalDate start = LocalDate.of(end.getYear(), (quarter - 1) * 3 + 1, 1);
        return new Period(end.getYear(), "Q" + quarter, start, end);
    }

    private String statementType(String dataset) {
        if (dataset == null) throw new IllegalArgumentException("Statement payload is missing dataset");
        return switch (dataset.trim().toLowerCase(Locale.ROOT).replace('-', '_')) {
            case "income_statement", "income" -> "INCOME_STATEMENT";
            case "balance_sheet", "balance" -> "BALANCE_SHEET";
            case "cash_flow", "cashflow" -> "CASH_FLOW";
            default -> throw new IllegalArgumentException("Unsupported financial statement dataset " + dataset);
        };
    }

    private String reportScope(String value) {
        if (value == null) return "UNKNOWN";
        String normalized = value.trim().toUpperCase(Locale.ROOT);
        return "CONSOLIDATED".equals(normalized) || "SEPARATE".equals(normalized) ? normalized : "UNKNOWN";
    }

    private static JsonNode first(JsonNode node, String... names) {
        for (String name : names) if (node.path(name).isValueNode()) return node.path(name);
        return null;
    }

    private static String firstText(JsonNode node, String... names) {
        JsonNode value = first(node, names);
        return value == null || value.isNull() || value.asText().isBlank() ? null : value.asText().trim();
    }

    private static BigDecimal decimal(JsonNode value) {
        if (value == null || value.isNull() || value.asText().isBlank()) return null;
        try { return value.isNumber() ? value.decimalValue() : new BigDecimal(value.asText().replace(",", "")); }
        catch (NumberFormatException exception) { return null; }
    }

    private static Integer integer(JsonNode value) {
        return value == null || value.isNull() ? null : value.canConvertToInt() ? value.asInt() : null;
    }

    private static String code(String value) {
        if (value == null || value.isBlank()) return null;
        return value.trim().toUpperCase(Locale.ROOT).replaceAll("[^A-Z0-9]+", "_").replaceAll("^_+|_+$", "");
    }

    /**
     * Providers can expose distinct accounting rows with the same English label.  Keep the
     * first readable normalized code, then make later collisions stable with the provider's
     * source item code so the database's per-statement item key remains unambiguous.
     */
    private static List<ItemDraft> uniqueItemCodes(List<ItemDraft> source) {
        Map<String, List<Integer>> positionsByCode = new LinkedHashMap<>();
        for (int index = 0; index < source.size(); index++) {
            positionsByCode.computeIfAbsent(source.get(index).itemCode(), ignored -> new ArrayList<>()).add(index);
        }
        List<ItemDraft> normalized = new ArrayList<>(source);
        for (List<Integer> positions : positionsByCode.values()) {
            if (positions.size() == 1) continue;
            // The suffix is derived from stable source identity, not provider row order.
            positions.sort(Comparator.comparing(index -> sourceIdentity(source.get(index))));
            Map<String, Integer> equalIdentityCount = new HashMap<>();
            for (int index : positions) {
                ItemDraft item = source.get(index);
                String identity = sourceIdentity(item);
                int duplicateNumber = equalIdentityCount.merge(identity, 1, Integer::sum);
                String candidate = item.itemCode() + "_" + shortSha256(identity);
                if (duplicateNumber > 1) candidate += "_" + duplicateNumber;
                normalized.set(
                        index,
                        new ItemDraft(candidate, item.itemName(), item.value(), item.rawValue(), item.displayOrder(), item.sourceItemCode(), item.sourceItemName(), item.provider()));
            }
        }
        return List.copyOf(normalized);
    }

    private static String sourceIdentity(ItemDraft item) {
        return (item.sourceItemCode() == null ? "" : item.sourceItemCode())
                + "\u001F"
                + (item.sourceItemName() == null ? "" : item.sourceItemName());
    }

    private static String shortSha256(String value) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8));
            StringBuilder result = new StringBuilder(12);
            for (int index = 0; index < 6; index++) result.append(String.format("%02X", digest[index]));
            return result.toString();
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
    }

    public record Period(int fiscalYear, String periodType, LocalDate startDate, LocalDate endDate) {}
    private record PeriodValue(Period period, JsonNode value) {}
    public record ItemDraft(String itemCode, String itemName, BigDecimal value, String rawValue, Integer displayOrder, String sourceItemCode, String sourceItemName, String provider) {}
    public record StatementDraft(RawPayloadEntity rawPayload, String symbol, String statementType, String reportScope, Period period, List<ItemDraft> items) {}
}
