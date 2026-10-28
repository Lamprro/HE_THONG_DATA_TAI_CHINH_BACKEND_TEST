package com.hethongdata.taichinh.service.financial;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import java.time.*;
import java.util.Set;

/** Explicit source metadata only. Provider fiscal/created/modified dates are never publication dates. */
public record FinancialStatementMetadata(String reportScope, String periodBasis, String currency,
        Long unitScale, Instant publishedAt, JsonNode provenance) {
    public static FinancialStatementMetadata parse(JsonNode root, LocalDate periodEnd, String statementType) {
        JsonNode metadata = root.path("financial_metadata");
        boolean contract = !metadata.isMissingNode();
        if (contract) {
            require(metadata.isObject() && "financial.metadata.v1".equals(metadata.path("schema_version").asText()),
                    "Unsupported financial metadata contract");
            require(!root.path("provider").asText().isBlank()
                    && root.path("provider").asText().equals(metadata.path("provider").asText()), "Metadata provider mismatch");
            if (metadata.path("periods").isObject() && !metadata.path("periods").isEmpty()) {
                require(metadata.path("periods").path(periodEnd.toString()).isObject(),
                        "Missing per-period metadata; aggregate publication must not be reused");
            }
            JsonNode perPeriod = metadata.path("periods").path(periodEnd.toString());
            if (perPeriod.isObject()) {
                require("financial.metadata.v1".equals(perPeriod.path("schema_version").asText())
                        && root.path("provider").asText().equals(perPeriod.path("provider").asText()),
                        "Per-period metadata contract/provider mismatch");
                metadata = perPeriod;
            }
        } else metadata = root;
        String scope = metadata.path("report_scope").asText(metadata.path("reportScope").asText("UNKNOWN"));
        String basis = metadata.path("period_basis").asText("UNKNOWN");
        require(Set.of("UNKNOWN", "CONSOLIDATED", "SEPARATE").contains(scope), "Invalid report scope");
        require(Set.of("UNKNOWN", "POINT_IN_TIME", "STANDALONE_QUARTER", "YTD", "ANNUAL").contains(basis), "Invalid period basis");
        if ("BALANCE_SHEET".equals(statementType)) basis = "POINT_IN_TIME";
        if (!contract && !"BALANCE_SHEET".equals(statementType)
                && java.util.Set.of("YEAR","ANNUAL","YEARLY").contains(root.path("report_type").asText())) basis="ANNUAL";
        String currency = metadata.hasNonNull("currency") ? metadata.path("currency").asText() : null;
        require(currency == null || currency.matches("[A-Z]{3}"), "Invalid statement currency");
        Long scale = null;
        if (metadata.hasNonNull("unit_scale")) {
            JsonNode value = metadata.get("unit_scale");
            require(value.isIntegralNumber() && value.canConvertToLong() && value.asLong()>0, "Invalid unit scale");
            scale = value.asLong();
        }
        Instant published = null;
        if (metadata.hasNonNull("published_at")) {
            try { published = OffsetDateTime.parse(metadata.path("published_at").asText()).toInstant(); }
            catch (RuntimeException e) { throw new IllegalArgumentException("Publication date must include a time zone"); }
            require(!published.isBefore(periodEnd.atStartOfDay(ZoneId.of("Asia/Ho_Chi_Minh")).toInstant()),
                    "Publication precedes fiscal period end");
            require(!published.isAfter(Instant.now()), "Publication date is in the future");
        }
        if (contract) {
            require(Set.of("UNKNOWN","ORIGINAL","RESTATED").contains(metadata.path("revision_status").asText("UNKNOWN")),"Invalid revision status");
            if(metadata.hasNonNull("revision_published_at")) {
                Instant revised=OffsetDateTime.parse(metadata.path("revision_published_at").asText()).toInstant();
                require(!revised.isAfter(Instant.now()) && (published==null || !revised.isBefore(published)),"Invalid restatement publication time");
            }
            for (String field : java.util.List.of("report_scope", "period_basis", "currency", "unit_scale", "published_at", "revision_status", "revision_published_at")) {
                JsonNode value = metadata.get(field);
                if (value==null || value.isNull() || "UNKNOWN".equals(value.asText())) continue;
                JsonNode evidence = metadata.path("evidence").path(field);
                require(evidence.isArray() && !evidence.isEmpty(), "Missing metadata evidence: " + field);
                boolean found = false;
                for (JsonNode source : evidence) if (value.equals(source.path("normalized"))) found = true;
                require(found, "Metadata evidence mismatch: " + field);
            }
        }
        var provenance = JsonNodeFactory.instance.objectNode();
        provenance.put("contract", contract ? "financial.metadata.v1" : "LEGACY_UNVERIFIED");
        if (contract) provenance.set("source", metadata.deepCopy());
        else provenance.put("limitation", "Legacy metadata has no explicit evidence; created/fiscal dates ignored");
        var missing = provenance.putArray("missing_training_metadata");
        if (!contract) missing.add("evidence_contract");
        if ("UNKNOWN".equals(scope)) missing.add("report_scope");
        if ("UNKNOWN".equals(basis)) missing.add("period_basis");
        if (currency == null) missing.add("currency");
        if (scale == null) missing.add("unit_scale");
        if (published == null) missing.add("published_at");
        if (!contract || !"ORIGINAL".equals(metadata.path("revision_status").asText())) missing.add("original_release_proof");
        provenance.put("metadata_completeness", missing.isEmpty() ? "COMPLETE_REQUIRES_SOURCE_REVIEW" : "INCOMPLETE");
        // Completeness is not proof that a current vendor value existed at publication.
        provenance.put("automatically_certifies_historical_vintage", false);
        return new FinancialStatementMetadata(scope, basis, currency, scale, published, provenance);
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new IllegalArgumentException(message);
    }
}
