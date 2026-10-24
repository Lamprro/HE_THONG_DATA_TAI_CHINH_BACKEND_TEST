package com.hethongdata.taichinh.service.financial;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import java.security.MessageDigest;
import java.time.*;
import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Audited source reviews. A residual explains a difference; it does not certify a quarterly label. */
@Service
public class FinancialReconciliationEvidenceService {
    private final JdbcTemplate jdbc;
    private final ObjectMapper json;
    public FinancialReconciliationEvidenceService(JdbcTemplate jdbc,ObjectMapper json) { this.jdbc=jdbc;this.json=json; }
    private static void require(boolean condition,String message) { if(!condition)throw new IllegalArgumentException(message); }

    @Transactional
    public Map<String,Object> save(JsonNode request) throws Exception {
        UUID itemId=UUID.fromString(request.path("itemId").asText());
        var item=jdbc.queryForMap("""
            SELECT i.value,i.item_code,s.security_id,se.symbol,p.end_date FROM financial_statement_items i
            JOIN financial_statements s ON s.id=i.financial_statement_id
            JOIN securities se ON se.id=s.security_id JOIN financial_periods p ON p.id=s.financial_period_id
            WHERE i.id=? AND s.is_current AND s.is_canonical
            """,itemId);
        require(item.get("item_code").equals(request.path("target").asText()),"REVIEW_TARGET");
        String method=request.path("method").asText();boolean difference=method.equals("YTD_DIFFERENCE");
        require(difference||method.equals("DIRECT_DOCUMENT"),"REVIEW_METHOD");
        JsonNode operands=request.path("operands");
        require(operands.isArray()&&operands.size()==(difference?2:1),"REVIEW_OPERANDS");
        require("ASSISTANT_VISUAL_REVIEW".equals(request.path("reviewMethod").asText()),"REVIEW_METHOD_NOT_HUMAN_CERTIFIED");
        BigDecimal result=BigDecimal.ZERO;Instant usable=Instant.EPOCH;
        LocalDate end=((java.sql.Date)item.get("end_date")).toLocalDate();
        String scope=null;
        for(int n=0;n<operands.size();n++) {
            var o=operands.get(n);long documentId=o.path("documentId").asLong();
            var doc=jdbc.queryForMap("SELECT symbol,report_scope,period_end,available_at,observed_at,metadata::text AS metadata FROM financial_disclosure_documents WHERE id=?",documentId);
            require(item.get("symbol").equals(doc.get("symbol")),"REVIEW_ISSUER");
            require(Set.of("CONSOLIDATED","SEPARATE").contains(doc.get("report_scope")),"REVIEW_SCOPE");
            if(scope==null)scope=doc.get("report_scope").toString();else require(scope.equals(doc.get("report_scope")),"REVIEW_SCOPE_CONFLICT");
            require(LocalDate.parse(o.path("periodEnd").asText()).equals(n==0?end:end.minusMonths(3).withDayOfMonth(1).plusMonths(1).minusDays(1)),"REVIEW_PERIOD");
            require(!difference||LocalDate.parse(o.path("periodStart").asText()).equals(LocalDate.of(end.getYear(),1,1)),"REVIEW_YTD_START");
            require(!difference||end.getMonthValue()>3,"REVIEW_Q1_DIFFERENCE");
            require(!LocalDate.parse(o.path("periodEnd").asText()).isAfter(((java.sql.Date)doc.get("period_end")).toLocalDate()),"REVIEW_DOCUMENT_PERIOD");
            require("VND".equals(o.path("currency").asText())&&o.path("unitScale").asInt()==1,"REVIEW_UNIT");
            require(o.path("page").asInt()>0&&o.path("page").asInt()<=1000&&!o.path("column").asText().isBlank(),"REVIEW_LOCATION");
            require(o.path("value").isNumber()&&o.path("parentValue").isNumber()&&o.path("minorityValue").isNumber(),"REVIEW_NUMBERS");
            BigDecimal value=o.path("value").decimalValue();
            require(value.compareTo(o.path("parentValue").decimalValue().add(o.path("minorityValue").decimalValue()))==0,"REVIEW_ACCOUNTING_IDENTITY");
            require("NET_PROFIT_AFTER_TAX".equals(request.path("target").asText()),"REVIEW_UNSUPPORTED_NUMERIC_CHECK");
            var raw=jdbc.queryForMap("""
                SELECT r.payload::text AS payload,r.source_symbol,ds.code,run.status,run.request_url
                FROM raw_payloads r JOIN data_sources ds ON ds.id=r.data_source_id
                JOIN ingestion_runs run ON run.id=r.ingestion_run_id WHERE r.id=?
                """,UUID.fromString(o.path("rawId").asText()));
            require(item.get("symbol").equals(raw.get("source_symbol"))&&"SUCCESS".equals(raw.get("status")),"REVIEW_RAW_SOURCE");
            var payload=json.readTree(raw.get("payload").toString());
            require("financial.disclosure.binary.v1".equals(payload.path("schema_version").asText()),"REVIEW_PDF_ENVELOPE");
            byte[] bytes=Base64.getDecoder().decode(payload.path("content_base64").asText());
            String sha=HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
            require(sha.equals(o.path("sha256").asText())&&sha.equals(payload.path("source_sha256").asText())&&bytes.length==payload.path("source_bytes").asInt(),"REVIEW_PDF_HASH");
            var source=OfficialFinancialSources.forSymbol(item.get("symbol").toString());
            require(source.code().equals(raw.get("code")),"REVIEW_PROVIDER");
            String path=java.net.URI.create(json.readTree(doc.get("metadata").toString()).path("document_url").asText()).getPath();
            require(java.net.URI.create(raw.get("request_url").toString()).getPath().equals("/api/v1/proxy/"+source.provider()+path),"REVIEW_DOCUMENT_RAW_LINK");
            for(String time:List.of("available_at","observed_at")) {
                Instant instant=((java.sql.Timestamp)doc.get(time)).toInstant();if(instant.isAfter(usable))usable=instant;
            }
            result=n==0?value:result.subtract(value);
        }
        // These are reviewed archival documents, so never backdate eligibility to their claimed publication.
        if(Instant.now().isAfter(usable))usable=Instant.now();
        String encoded=request.toString(),hash=HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(encoded.getBytes(java.nio.charset.StandardCharsets.UTF_8)));
        BigDecimal canonical=(BigDecimal)item.get("value");
        int inserted=jdbc.update("""
            INSERT INTO financial_reconciliation_evidence(item_id,canonical_value,evidence_value,method,evidence_sha256,evidence,usable_from)
            VALUES (?,?,?,?,?,?::jsonb,?::timestamptz) ON CONFLICT DO NOTHING
            """,itemId,canonical,result,method,hash,encoded,usable.toString());
        return Map.of("inserted",inserted,"canonicalValue",canonical,"evidenceValue",result,"difference",canonical.subtract(result),"trainingEligible",false);
    }
    public List<Map<String,Object>> list(String symbol) {
        require(symbol!=null&&symbol.matches("[A-Z0-9._-]{1,20}"),"INVALID_SYMBOL");
        return jdbc.queryForList("""
            SELECT r.id,p.end_date,i.item_code,r.canonical_value,r.evidence_value,r.canonical_value-r.evidence_value AS difference,
                r.method,r.evidence,r.usable_from,r.training_eligible,(i.value IS DISTINCT FROM r.canonical_value) AS stale_review
            FROM financial_reconciliation_evidence r JOIN financial_statement_items i ON i.id=r.item_id
            JOIN financial_statements s ON s.id=i.financial_statement_id JOIN securities se ON se.id=s.security_id
            JOIN financial_periods p ON p.id=s.financial_period_id WHERE se.symbol=? ORDER BY r.id DESC LIMIT 200
            """,symbol);
    }
}
