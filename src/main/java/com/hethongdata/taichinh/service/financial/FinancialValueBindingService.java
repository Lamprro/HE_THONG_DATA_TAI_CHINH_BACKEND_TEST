package com.hethongdata.taichinh.service.financial;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Binds reviewed field facts, never an entire statement based on its ticker/quarter alone. */
@Service
public class FinancialValueBindingService {
    private final JdbcTemplate jdbc;
    public FinancialValueBindingService(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    @Transactional
    public Map<String,Object> reconcile(String symbol) {
        validateSymbol(symbol);
        int inserted = jdbc.update("""
            INSERT INTO financial_value_bindings(item_id,fact_id,document_id,canonical_value,
                document_value,document_sha256,comparison,source_available_at,usable_from,evidence)
            SELECT i.id,f.id,d.id,i.value,f.value,f.source_sha256,
                CASE WHEN i.value=f.value THEN 'EXACT_VALUE_MATCH' ELSE 'VALUE_CONFLICT' END,
                GREATEST(f.available_at,d.available_at),
                GREATEST(f.available_at,d.available_at,d.observed_at,f.created_at),
                jsonb_build_object('method','REVIEWED_FACT_DOCUMENT_JOIN','sourcePage',f.source_page,
                    'sourceDocumentRawId',f.source_document_raw_id,'sourceListingRawId',f.source_listing_raw_id,
                    'factValidation',f.validation_evidence,'documentRecordSha256',d.record_sha256,
                    'historicalVintage','ARCHIVED_RECONSTRUCTION','statementMetadataModified',false)
            FROM financial_disclosure_facts f
            JOIN securities se ON se.id=f.security_id
            JOIN (SELECT DISTINCT ON(source_code,document_id) * FROM financial_disclosure_documents ORDER BY source_code,document_id,observed_at DESC,parser_version DESC,id DESC) d ON d.symbol=se.symbol
              AND d.metadata->>'document_url'=f.source_document_url
              AND d.period_end>=f.period_end AND d.published_on=f.published_on
              AND d.report_scope=f.report_scope
            JOIN raw_payloads raw ON raw.id=f.source_document_raw_id
              AND raw.payload->>'source_sha256'=f.source_sha256
              AND raw.payload->>'schema_version'='financial.disclosure.binary.v1'
            JOIN financial_statements s ON s.security_id=f.security_id AND s.is_current AND s.is_canonical
              AND s.statement_type='INCOME_STATEMENT'
            JOIN financial_periods p ON p.id=s.financial_period_id AND p.end_date=f.period_end
              AND p.period_type IN ('Q1','Q2','Q3','Q4')
            JOIN financial_statement_items i ON i.financial_statement_id=s.id AND i.item_code=f.target_code
            WHERE se.symbol=? AND f.report_scope IN ('CONSOLIDATED','SEPARATE')
              AND f.period_basis='STANDALONE_QUARTER' AND f.currency='VND' AND f.unit_scale=1
              AND f.eligibility='RESEARCH_ONLY'
            ON CONFLICT DO NOTHING
            """, symbol);
        return Map.of("symbol",symbol,"insertedBindings",inserted,"vendorStatementsModified",0,
                "historicalTrainingStatus","ARCHIVED_RESEARCH_ONLY");
    }

    public List<Map<String,Object>> list(String symbol, Instant asOf, int limit) {
        validateSymbol(symbol);
        if (limit<1 || limit>500) throw new IllegalArgumentException("Limit must be 1..500");
        // Both exact and conflict rows are returned for review. This is not an automatic training export.
        return jdbc.queryForList("""
            SELECT id,item_id,fact_id,document_id,period_end,item_code,canonical_value,document_value,
                current_value,comparison,report_scope,period_basis,currency,unit_scale,source_page,
                report_version_id,revision_status,document_url,document_sha256,source_available_at,
                usable_from,stale_binding,historical_training_status,
                (NOT stale_binding AND comparison='EXACT_VALUE_MATCH' AND usable_from<=?::timestamptz)
                    AS observed_value_available
            FROM vw_financial_value_bindings WHERE symbol=?
            ORDER BY period_end DESC,source_available_at DESC,id DESC LIMIT ?
            """,asOf.toString(),symbol,limit);
    }

    private static void validateSymbol(String symbol) {
        if(symbol==null || !symbol.matches("[A-Z0-9._-]{1,20}")) throw new IllegalArgumentException("Invalid symbol");
    }
}
