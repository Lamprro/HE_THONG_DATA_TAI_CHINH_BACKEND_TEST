package com.hethongdata.taichinh.service.llm;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.hethongdata.taichinh.service.ingestion.ChecksumService;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;

@Service
public class NewsLlmContext {
    public record Context(JsonNode input, String sourceHash, List<String> issues) {
        public boolean eligible() { return issues.isEmpty(); }
    }
    private final JdbcTemplate db;
    private final LlmJson json;
    private final ChecksumService hashes;
    public NewsLlmContext(JdbcTemplate db,LlmJson json,ChecksumService hashes) { this.db=db; this.json=json; this.hashes=hashes; }
    public Context build(UUID id,String task) {
        var rows=db.queryForList("SELECT * FROM news_articles WHERE id=?",id);
        if(rows.isEmpty()) throw new IllegalArgumentException("News article not found: "+id);
        var row=rows.getFirst();
        var issues=new java.util.ArrayList<String>();
        String content=(String)row.get("content_text"), title=(String)row.get("title");
        String url=(String)row.get("canonical_url"), hash=(String)row.get("content_hash");
        JsonNode metadata=json.read(row.get("metadata").toString());
        if(content==null || content.strip().length()<200) issues.add("BODY_MISSING_OR_TOO_SHORT");
        if(content!=null && content.length()>150000) issues.add("BODY_EXCEEDS_CONTEXT_LIMIT");
        if(title==null || title.isBlank()) issues.add("TITLE_MISSING");
        if(row.get("published_at")==null) issues.add("PUBLISHED_AT_MISSING");
        if(!"UNIQUE".equals(row.get("dedup_status")) || row.get("duplicate_of_news_article_id")!=null) issues.add("DUPLICATE_OR_UNRESOLVED");
        if(Boolean.TRUE.equals(row.get("is_deleted_source"))) issues.add("DELETED_SOURCE");
        if(metadata.path("llm_excluded").asBoolean() || metadata.path("is_test").asBoolean()
                || (url!=null && url.matches("(?i).*(real-db-|localhost|example\\.com|/test/).*"))) issues.add("TEST_OR_EXCLUDED_SOURCE");
        if(content!=null && content.contains("Giá hiện tại Thay đổi Xem hồ sơ doanh nghiệp TIN MỚI")) issues.add("PAGE_CHROME_IN_BODY");
        if(content!=null && !hashes.sha256(content).equals(hash)) issues.add("CONTENT_HASH_MISMATCH");
        ObjectNode article=json.mapper().createObjectNode();
        article.put("id",id.toString()).put("title",title==null?"":title).put("sapo",(String)row.getOrDefault("sapo",null));
        if(article.path("sapo").isNull()) article.put("sapo","");
        String published="";
        Object date=row.get("published_at");
        if(date instanceof java.sql.Timestamp t) published=t.toInstant().atOffset(ZoneOffset.ofHours(7)).toString();
        else if(date instanceof OffsetDateTime t) published=t.withOffsetSameInstant(ZoneOffset.ofHours(7)).toString();
        article.put("published_at",published).put("url",url==null?"":url).put("content_hash",hash==null?"":hash);
        var segments=article.putArray("segments");
        // Preserve exact contiguous text for quote validation; no silent truncation.
        if(content!=null) {int index=1;for(String text:segmentText(content))
            segments.addObject().put("id","p"+index++).put("text",text);}
        var companies=article.putArray("companies");
        db.query("""
            SELECT n.company_id,n.security_id,c.legal_name,s.symbol,n.match_method,n.match_evidence
            FROM news_article_companies n JOIN companies c ON c.id=n.company_id
            LEFT JOIN securities s ON s.id=n.security_id WHERE n.news_article_id=?
            ORDER BY n.company_id,n.security_id NULLS FIRST,n.id
            """,rs->{
                var company=companies.addObject();
                company.put("company_id",rs.getString(1)).put("security_id",rs.getString(2)).put("name",rs.getString(3))
                        .put("symbol",rs.getString(4)).put("match_method",rs.getString(5));
                company.set("match_evidence",rs.getString(6)==null?json.mapper().nullNode():json.read(rs.getString(6)));
            },id);
        ObjectNode input=json.mapper().createObjectNode();
        input.put("schema_version","news.input.v1").put("task_code",task).set("article",article);
        return new Context(input,hashes.sha256(article.toString()),List.copyOf(issues));
    }
    static List<String> segmentText(String content) {
        var result=new java.util.ArrayList<String>();
        var sentences=java.text.BreakIterator.getSentenceInstance(java.util.Locale.forLanguageTag("vi"));
        sentences.setText(content);
        for(int start=0;start<content.length();) {
            int end=Math.min(start+1800,content.length());
            if(end<content.length()) {
                int boundary=sentences.preceding(end+1);
                if(boundary>start) end=boundary;
                else {int space=content.lastIndexOf(' ',end);if(space>start) end=space;}
                if(Character.isHighSurrogate(content.charAt(end-1))) end--;
            }
            result.add(content.substring(start,end));start=end;
        }
        return List.copyOf(result);
    }
}
