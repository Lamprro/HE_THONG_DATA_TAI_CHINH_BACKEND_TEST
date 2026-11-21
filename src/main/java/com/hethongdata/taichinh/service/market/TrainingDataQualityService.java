package com.hethongdata.taichinh.service.market;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.hethongdata.taichinh.repository.jdbc.market.TrainingQualityQueryRepository;
import com.hethongdata.taichinh.service.financial.FinancialStatementMetadata;
import java.sql.Timestamp;
import java.util.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Reviews immutable source payloads; never manufactures economic observations. */
@Service
public class TrainingDataQualityService {
    private final TrainingQualityQueryRepository repo;
    private final ObjectMapper json;
    private final MarketPricePayloadParser parser;

    public TrainingDataQualityService(TrainingQualityQueryRepository repo, ObjectMapper json, MarketPricePayloadParser parser) {
        this.repo = repo; this.json = json; this.parser = parser;
    }

    @Transactional
    public Map<String, Object> reviewLegacy(String symbol) throws Exception {
        if (symbol == null || !symbol.matches("[A-Z0-9._-]{1,20}")) throw new IllegalArgumentException("Invalid symbol");
        UUID security = repo.findSecurityIdForUpdate(symbol);
        int annotated = 0; var issues = new ArrayList<String>();
        var raws = repo.findRawPayloadsForSecurityPrices(security);
        for (var raw : raws) {
            var root = json.readTree((String) raw.get("body")); String provider = root.path("provider").asText();
            if (!Set.of("vndirect", "cafef").contains(provider)) continue;
            try {
                var rows = parser.parse(root, (String) raw.get("entity_type"), symbol, ((Timestamp) raw.get("fetched_at")).toInstant()).rows();
                var metadata = json.createObjectNode().put("contract", "price.source-review.v1").put("provider", provider)
                    .put("currency", "VND").put("sourceRawId", raw.get("id").toString())
                    .put("adjustmentMethod", "UNKNOWN").put("historicalVintageVerified", false)
                    .put("limitation", "Provider adjusted close is a current source vintage; corporate actions and past revisions are not certified");
                var args = new ArrayList<Object[]>();
                for (var row : rows) if (row.adjustedClose() != null) args.add(new Object[]{metadata.toString(), raw.get("fetched_at"), security,
                    raw.get("id"), Timestamp.from(row.timestamp()), row.open(), row.high(), row.low(), row.close(), row.adjustedClose()});
                int[] counts = repo.batchUpdateMarketPrices(args);
                for (int count : counts) if (count > 0) annotated += count;
            } catch (IllegalArgumentException e) { issues.add(raw.get("id") + ": " + e.getMessage()); }
        }
        repo.demoteCanonicalMarketPrices(security);
        repo.promoteCanonicalMarketPrices(security);
        int statements = 0;
        for (var row : repo.findLegacyFinancialStatements(security)) {
            var metadata = FinancialStatementMetadata.parse(json.readTree((String) row.get("body")),
                ((java.sql.Date) row.get("end_date")).toLocalDate(), (String) row.get("statement_type"));
            var evidence = json.createObjectNode().put("contract", "LEGACY_RAW_REVIEW_V1")
                .put("rawPayloadId", row.get("raw_id").toString()).put("periodBasis", metadata.periodBasis())
                .put("unitAndCurrencyVerified", false).put("historicalPublicationVerified", false)
                .put("limitation", "Legacy currency/scale retained for compatibility; quarterly flow basis, scope and publication require explicit source evidence");
            statements += repo.updateFinancialStatementMetadata(metadata.periodBasis(), evidence.toString(), (UUID) row.get("id"));
        }
        return Map.of("symbol", symbol, "priceRowsVerified", annotated, "statementMetadataReviewed", statements, "sourceIssues", issues);
    }

    public Map<String, Object> coverage() {
        return Map.of(
            "observedAt", java.time.Instant.now().toString(),
            "securities", repo.findCoverageSecurities(),
            "statements", repo.findCoverageStatements(),
            "strictPointInTimeEligibleItems", repo.countStrictPointInTimeEligibleItems(),
            "limitation", "Current source snapshots do not establish historical availability, sector membership or a survivorship-free universe");
    }
}
