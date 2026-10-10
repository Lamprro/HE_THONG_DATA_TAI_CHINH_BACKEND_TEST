package com.hethongdata.taichinh.service.llm;

import com.fasterxml.jackson.databind.JsonNode;
import com.hethongdata.taichinh.service.ingestion.ChecksumService;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.util.List;
import java.util.UUID;

@Service
public class LlmPromptCatalog {
    public static final List<String> TASKS = List.of("NEWS_SUMMARY", "NEWS_DETAIL", "NEWS_FINANCIAL_FACTS");
    public record Template(UUID id, String task, String version, String prompt,
                           JsonNode requestSchema, JsonNode responseSchema, String checksum) {}
    private final JdbcTemplate db;
    private final LlmJson json;
    private final ChecksumService hashes;
    public LlmPromptCatalog(JdbcTemplate db, LlmJson json, ChecksumService hashes) {
        this.db=db; this.json=json; this.hashes=hashes;
    }
    @Transactional
    public int seed() {
        int count=0;
        for (String task : TASKS) {
            JsonNode resource;
            try (var stream=new ClassPathResource("llm/"+task.toLowerCase(java.util.Locale.ROOT)+".json").getInputStream()) {
                resource=json.mapper().readTree(stream);
            } catch(Exception e) { throw new IllegalStateException("Cannot load prompt "+task,e); }
            String checksum=hashes.sha256(resource.toString());
            db.queryForObject("SELECT 1 FROM pg_advisory_xact_lock(hashtext(?))",Integer.class,"llm-prompt:"+task);
            var existing=db.queryForList("SELECT checksum FROM llm_prompt_templates WHERE task_code=? AND version=?",
                    String.class, task, resource.path("version").asText());
            if (!existing.isEmpty()) {
                if (!existing.getFirst().equals(checksum)) throw new IllegalStateException("Prompt is immutable; increment version: "+task);
                continue;
            }
            // Explicit seeding promotes a new immutable version atomically; previous runs keep their FK.
            db.update("UPDATE llm_prompt_templates SET enabled=false WHERE task_code=? AND enabled",task);
            count+=db.update("""
                INSERT INTO llm_prompt_templates(id,task_code,version,input_domain,description,system_prompt,
                    request_schema,response_schema,checksum,enabled)
                VALUES (?,?,?,?,?,?,?::jsonb,?::jsonb,?,true) ON CONFLICT(task_code,version) DO NOTHING
                """,UUID.randomUUID(),task,resource.path("version").asText(),resource.path("input_domain").asText(),
                    resource.path("description").asText(),resource.path("system_prompt").asText(),
                    resource.path("request_schema").toString(),resource.path("response_schema").toString(),checksum);
        }
        return count;
    }
    public Template active(String task) {
        if (!TASKS.contains(task)) throw new IllegalArgumentException("Unsupported NEWS task: "+task);
        return db.query("SELECT * FROM llm_prompt_templates WHERE task_code=? AND enabled AND input_domain='NEWS'",
                (rs,n)->new Template(rs.getObject("id",UUID.class),task,rs.getString("version"),rs.getString("system_prompt"),
                        json.read(rs.getString("request_schema")),json.read(rs.getString("response_schema")),rs.getString("checksum")),task)
                .stream().findFirst().orElseThrow(()->new IllegalArgumentException("No active prompt: "+task));
    }
    public List<Template> activeTemplates() {
        return db.query("SELECT * FROM llm_prompt_templates WHERE enabled AND input_domain='NEWS' ORDER BY task_code",
                (rs,n)->new Template(rs.getObject("id",UUID.class),rs.getString("task_code"),rs.getString("version"),
                        rs.getString("system_prompt"),json.read(rs.getString("request_schema")),
                        json.read(rs.getString("response_schema")),rs.getString("checksum")));
    }
}
