package com.hethongdata.taichinh.service.financial;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.hethongdata.taichinh.entity.ingestion.IngestionRunEntity;
import com.hethongdata.taichinh.repository.jdbc.financial.FinancialMetricJdbcRepository;
import com.hethongdata.taichinh.repository.jpa.validation.DataVersionJpaRepository;
import com.hethongdata.taichinh.repository.jpa.ingestion.IngestionRunJpaRepository;
import com.hethongdata.taichinh.repository.jpa.ingestion.RawPayloadJpaRepository;
import com.hethongdata.taichinh.service.ingestion.ChecksumService;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Atomic materialization of validated RATIO batches, with source selection and replay audit. */
@Service
public class FinancialMetricBuildPersistenceService {
    private final FinancialMetricJdbcRepository metricRepo;
    private final DataVersionJpaRepository versions;
    private final RawPayloadJpaRepository raws;
    private final IngestionRunJpaRepository runs;
    private final ObjectMapper mapper;
    private final ChecksumService hashes;

    public FinancialMetricBuildPersistenceService(FinancialMetricJdbcRepository metricRepo, DataVersionJpaRepository versions,
            RawPayloadJpaRepository raws, IngestionRunJpaRepository runs, ObjectMapper mapper, ChecksumService hashes) {
        this.metricRepo = metricRepo; this.versions = versions; this.raws = raws;
        this.runs = runs; this.mapper = mapper; this.hashes = hashes;
    }

    @Transactional
    public int build(UUID versionId, IngestionRunEntity run) {
        var version = versions.findByIdForUpdate(versionId).orElseThrow();
        if (!"ACTIVE".equals(version.getStatus()) || !"FINANCIAL_METRIC".equals(version.getDataDomain()))
            throw new IllegalStateException("Version is no longer an active FINANCIAL_METRIC batch");
        var payloads = raws.findByIngestionRunIdOrderByFetchedAtDesc(version.getIngestionRunId());
        if (payloads.isEmpty()) throw new IllegalArgumentException("No raw ratio payloads");
        int inserted = 0;
        for (var raw : payloads) {
            if (!"RATIO".equals(raw.getEntityType())) throw new IllegalArgumentException("Batch contains non-RATIO payload");
            var rows = parseRatios(raw.getPayload(), raw.getSourceSymbol());
            var security = metricRepo.findSecurityCompanyBySymbol(raw.getSourceSymbol());
            if (security.size() != 1 || security.getFirst().get("company_id") == null)
                throw new IllegalArgumentException("Missing company/security mapping");
            UUID securityId = (UUID) security.getFirst().get("id");
            UUID companyId = (UUID) security.getFirst().get("company_id");
            if (raw.getSecurityId() != null && !raw.getSecurityId().equals(securityId))
                throw new IllegalArgumentException("Raw security ID differs from requested symbol");
            metricRepo.lockCompanyForUpdate(companyId);
            Map<String, Long> definitions = new HashMap<>();
            List<Object[]> parameters = new ArrayList<>();
            int before = metricRepo.countMetricsByRawId(raw.getId(), "provider-ratio-v1");
            for (var ratio : rows) {
                Long definition = definitions.computeIfAbsent(ratio.code(), code ->
                    metricRepo.findMetricDefinitionIdByCode(code));
                var snapshot = mapper.createObjectNode().put("contract", "vndirect-ratio-v1")
                        .put("sourceRawId", raw.getId().toString()).put("sourceChecksum", raw.getChecksumSha256())
                        .put("sourceValue", ratio.sourceValue()).put("transformation", ratio.transformation())
                        .put("sourceAvailableAt", raw.getFetchedAt().toString())
                        .put("systemFirstSeenAt", raw.getFetchedAt().toString())
                        .put("historicalPublicationVerified", false)
                        .put("limitation", "reportDate is the source observation period, not verified historical publication. Current source vintage retained.");
                snapshot.set("sourceRow", ratio.sourceRow());
                String key = hashes.sha256("provider-ratio-v1:" + raw.getId() + ":" + ratio.code() + ":" + ratio.date());
                parameters.add(new Object[]{companyId, securityId, definition, ratio.date(), ratio.value(),
                    raw.getDataSource().getId(), raw.getId(), versionId, snapshot.toString(), key});
            }
            metricRepo.batchInsertProviderRatios(parameters);
            inserted += metricRepo.countMetricsByRawId(raw.getId(), "provider-ratio-v1") - before;
            metricRepo.reconcileBatch(companyId, raw.getId());
        }
        run.markBatchSuccess(mapper.valueToTree(Map.of("workflow", "FINANCIAL_METRIC_BUILD",
                "sourceVersionId", versionId, "metricCount", inserted)), Instant.now(), inserted);
        runs.save(run); version.markActivated(); versions.save(version); return inserted;
    }

    @Transactional
    public void noWork(IngestionRunEntity run) {
        run.markBatchSuccess(mapper.valueToTree(Map.of("workflow", "FINANCIAL_METRIC_BUILD", "noWork", true)), Instant.now(), 0);
        runs.save(run);
    }

    // ---- Explicit VNDirect ratio contract; no magnitude-based guessing and no forecast inputs. ----
    private static final Set<String> RATIO_CODES = Set.of("PRICE_TO_EARNINGS", "PRICE_TO_BOOK", "EPS_TR",
            "ROAE_TR_AVG5Q", "ROAA_TR_AVG5Q", "DIVIDEND_YIELD");
    private static final Set<String> FRACTION_RATIOS = Set.of("ROAE_TR_AVG5Q", "ROAA_TR_AVG5Q", "DIVIDEND_YIELD");
    public record Ratio(String code, LocalDate date, BigDecimal value, BigDecimal sourceValue,
                        String transformation, JsonNode sourceRow) {}

    public static List<Ratio> parseRatios(JsonNode payload, String sourceSymbol) {
        if (!"vndirect".equalsIgnoreCase(payload.path("provider").asText())
                || !"ratios".equalsIgnoreCase(payload.path("dataset").asText()))
            throw new IllegalArgumentException("Only the reviewed VNDirect ratios contract is supported");
        if (!sourceSymbol.equalsIgnoreCase(payload.path("symbol").asText())
                || !payload.path("data").isArray() || payload.path("data").isEmpty()
                || payload.path("count").asInt(-1) != payload.path("data").size())
            throw new IllegalArgumentException("Invalid ratio symbol/count/data envelope");
        var out = new ArrayList<Ratio>();
        var identities = new HashSet<String>();
        for (JsonNode row : payload.path("data")) {
            String code = row.path("ratioCode").asText();
            if (!RATIO_CODES.contains(code) || !sourceSymbol.equalsIgnoreCase(row.path("code").asText()))
                throw new IllegalArgumentException("Unknown ratio code or mismatched security");
            LocalDate date = LocalDate.parse(row.path("reportDate").asText());
            if (date.isAfter(LocalDate.now(java.time.ZoneId.of("Asia/Ho_Chi_Minh"))))
                throw new IllegalArgumentException("Ratio report date is in the future");
            if (!row.path("value").isNumber()) throw new IllegalArgumentException("Missing numeric ratio value");
            if (!identities.add(code + ":" + date)) throw new IllegalArgumentException("Duplicate ratio/date in batch");
            BigDecimal original = row.path("value").decimalValue();
            boolean fraction = FRACTION_RATIOS.contains(code);
            out.add(new Ratio(code, date, fraction ? original.multiply(new BigDecimal("100")) : original,
                    original, fraction ? "fraction_to_percent:*100" : "identity", row.deepCopy()));
        }
        return List.copyOf(out);
    }
}
