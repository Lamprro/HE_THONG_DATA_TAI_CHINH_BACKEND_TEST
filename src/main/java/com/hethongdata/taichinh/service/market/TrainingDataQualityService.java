package com.hethongdata.taichinh.service.market;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.hethongdata.taichinh.service.financial.FinancialStatementMetadata;
import java.sql.Timestamp;
import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Reviews immutable source payloads; never manufactures economic observations. */
@Service
public class TrainingDataQualityService {
    private final JdbcTemplate db; private final ObjectMapper json; private final MarketPricePayloadParser parser;
    public TrainingDataQualityService(JdbcTemplate db,ObjectMapper json,MarketPricePayloadParser parser) {
        this.db=db;this.json=json;this.parser=parser;
    }
    @Transactional public Map<String,Object> reviewLegacy(String symbol) throws Exception {
        if(symbol==null || !symbol.matches("[A-Z0-9._-]{1,20}")) throw new IllegalArgumentException("Invalid symbol");
        UUID security=db.queryForObject("SELECT id FROM securities WHERE symbol=? FOR UPDATE",UUID.class,symbol);
        int annotated=0;var issues=new ArrayList<String>();
        var raws=db.queryForList("""
SELECT r.id,r.payload::text body,r.entity_type,r.fetched_at FROM raw_payloads r
WHERE EXISTS(SELECT 1 FROM market_prices m WHERE m.raw_payload_id=r.id AND m.security_id=? AND m.interval_code='1d')
""",security);
        for(var raw:raws) {
            var root=json.readTree((String)raw.get("body"));String provider=root.path("provider").asText();
            if(!Set.of("vndirect","cafef").contains(provider)) continue;
            try {
                var rows=parser.parse(root,(String)raw.get("entity_type"),symbol,((Timestamp)raw.get("fetched_at")).toInstant()).rows();
                var metadata=json.createObjectNode().put("contract","price.source-review.v1").put("provider",provider)
                    .put("currency","VND").put("sourceRawId",raw.get("id").toString())
                    .put("adjustmentMethod","UNKNOWN").put("historicalVintageVerified",false)
                    .put("limitation","Provider adjusted close is a current source vintage; corporate actions and past revisions are not certified");
                var args=new ArrayList<Object[]>();
                for(var row:rows) if(row.adjustedClose()!=null) args.add(new Object[]{metadata.toString(),raw.get("fetched_at"),security,
                    raw.get("id"),Timestamp.from(row.timestamp()),row.open(),row.high(),row.low(),row.close(),row.adjustedClose()});
                int[] counts=db.batchUpdate("""
UPDATE market_prices SET price_basis='UNADJUSTED',price_metadata=?::jsonb,first_seen_at=coalesce(first_seen_at,?)
WHERE security_id=? AND raw_payload_id=? AND price_timestamp=? AND interval_code='1d'
AND open_price IS NOT DISTINCT FROM ?::numeric AND high_price IS NOT DISTINCT FROM ?::numeric
AND low_price IS NOT DISTINCT FROM ?::numeric AND close_price=? AND adjusted_close=?
""",args);
                for(int count:counts) if(count>0) annotated+=count;
            } catch(IllegalArgumentException e) {issues.add(raw.get("id")+": "+e.getMessage());}
        }
        db.update("UPDATE market_prices SET is_canonical=false WHERE security_id=? AND interval_code='1d' AND is_canonical",security);
        db.update("""
WITH ranked AS (SELECT m.id,row_number() OVER(PARTITION BY m.price_timestamp ORDER BY
 (m.price_basis='UNADJUSTED' AND m.adjusted_close IS NOT NULL) DESC,d.is_official DESC,d.priority,d.id,m.id) n
 FROM market_prices m JOIN data_sources d ON d.id=m.data_source_id JOIN data_versions v ON v.id=m.data_version_id
 WHERE m.security_id=? AND m.interval_code='1d' AND v.status='ACTIVATED')
UPDATE market_prices m SET is_canonical=true FROM ranked r WHERE m.id=r.id AND r.n=1
""",security);
        int statements=0;
        for(var row:db.queryForList("""
SELECT s.id,s.statement_type,p.end_date,r.payload::text body,r.id raw_id FROM financial_statements s
JOIN financial_periods p ON p.id=s.financial_period_id JOIN raw_payloads r ON r.id=s.raw_payload_id
WHERE s.security_id=? AND coalesce(s.metadata_provenance->>'contract','')<>'financial.metadata.v1'
""",security)) {
            var metadata=FinancialStatementMetadata.parse(json.readTree((String)row.get("body")),
                ((java.sql.Date)row.get("end_date")).toLocalDate(),(String)row.get("statement_type"));
            var evidence=json.createObjectNode().put("contract","LEGACY_RAW_REVIEW_V1")
                .put("rawPayloadId",row.get("raw_id").toString()).put("periodBasis",metadata.periodBasis())
                .put("unitAndCurrencyVerified",false).put("historicalPublicationVerified",false)
                .put("limitation","Legacy currency/scale retained for compatibility; quarterly flow basis, scope and publication require explicit source evidence");
            statements+=db.update("UPDATE financial_statements SET period_basis=?,metadata_provenance=?::jsonb,is_restated=NULL WHERE id=?",
                metadata.periodBasis(),evidence.toString(),row.get("id"));
        }
        return Map.of("symbol",symbol,"priceRowsVerified",annotated,"statementMetadataReviewed",statements,"sourceIssues",issues);
    }
    public Map<String,Object> coverage() {
        return Map.of("observedAt",java.time.Instant.now().toString(),"securities",db.queryForList("""
SELECT se.symbol,count(m.id) FILTER(WHERE m.is_canonical) daily_prices,
min(m.price_timestamp) FILTER(WHERE m.is_canonical) first_price,max(m.price_timestamp) FILTER(WHERE m.is_canonical) last_price,
count(m.id) FILTER(WHERE m.is_canonical AND m.adjusted_close IS NULL) missing_adjusted_close,
count(m.id) FILTER(WHERE m.is_canonical AND m.price_basis='UNKNOWN') unknown_price_basis,
count(m.id) FILTER(WHERE m.is_canonical AND m.foreign_raw_payload_id IS NOT NULL) foreign_source_days
FROM securities se LEFT JOIN market_prices m ON m.security_id=se.id AND m.interval_code='1d' GROUP BY se.id ORDER BY se.symbol
"""),"statements",db.queryForList("""
SELECT statement_type,report_scope,period_basis,count(*) count,count(*) FILTER(WHERE published_at IS NULL) missing_published_at,
count(*) FILTER(WHERE currency IS NULL OR unit_scale IS NULL) missing_units FROM financial_statements
WHERE is_current AND is_canonical GROUP BY statement_type,report_scope,period_basis
"""),"strictPointInTimeEligibleItems",db.queryForObject("SELECT count(*) FROM vw_training_statement_inputs_v1 WHERE strict_point_in_time_eligible",Long.class),
"limitation","Current source snapshots do not establish historical availability, sector membership or a survivorship-free universe");
    }
}
