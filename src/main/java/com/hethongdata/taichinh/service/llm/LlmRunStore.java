package com.hethongdata.taichinh.service.llm;

import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.util.*;

@Service
public class LlmRunStore {
    public record Outcome(String status,UUID runId,UUID resultId,List<String> issues) {}
    private final JdbcTemplate db;
    private final LlmJson json;
    private final NewsLlmContext contexts;
    private final LlmValidationService validations;
    private final com.hethongdata.taichinh.service.ingestion.ChecksumService hashes;
    public LlmRunStore(JdbcTemplate db,LlmJson json,NewsLlmContext contexts,LlmValidationService validations,
            com.hethongdata.taichinh.service.ingestion.ChecksumService hashes) {
        this.db=db;this.json=json;this.contexts=contexts;this.validations=validations;
        this.hashes=hashes;
    }
    private void lock(UUID article,String task) {
        db.queryForList("SELECT pg_advisory_xact_lock(hashtextextended(?,0))",article+":"+task);
    }
    @Transactional
    public Outcome claim(UUID article,LlmPromptCatalog.Template template,NewsLlmContext.Context context,
                         String inputHash,String model,JsonNode request) {
        return claim(article,template,context,inputHash,model,request,model);
    }
    @Transactional
    public Outcome claim(UUID article,LlmPromptCatalog.Template template,NewsLlmContext.Context context,
                         String inputHash,String model,JsonNode request,String routingKey) {
        lock(article,template.task());
        var cached=db.queryForList("SELECT id,llm_run_id FROM llm_results WHERE news_article_id=? AND task_code=? AND input_hash=? AND is_current",
                article,template.task(),inputHash);
        if(!cached.isEmpty()) return new Outcome("CACHED",(UUID)cached.getFirst().get("llm_run_id"),(UUID)cached.getFirst().get("id"),List.of());
        db.update("""
            UPDATE llm_runs SET status='FAILED',error_message='RUN_LEASE_EXPIRED',finished_at=now()
            WHERE news_article_id=? AND task_code=? AND status='RUNNING' AND created_at < now()-interval '10 minutes'
            """,article,template.task());
        var running=db.queryForList("SELECT id FROM llm_runs WHERE news_article_id=? AND task_code=? AND status IN ('RUNNING','PENDING_VALIDATION')",UUID.class,article,template.task());
        if(!running.isEmpty()) return new Outcome("RUNNING",running.getFirst(),null,List.of());
        Integer attempts=db.queryForObject("SELECT count(*) FROM llm_runs WHERE news_article_id=? AND task_code=? AND input_hash=? AND status IN ('FAILED','REJECTED')",
                Integer.class,article,template.task(),inputHash);
        if(attempts!=null && attempts>=3) return new Outcome("RETRY_LIMIT",null,null,List.of("Three failed attempts for unchanged input/model/template"));
        UUID run=UUID.randomUUID();
        var metadata=json.mapper().createObjectNode();
        metadata.set("input",context.input());
        metadata.put("source_hash",context.sourceHash()).put("template_checksum",template.checksum()).put("routing_key",routingKey);
        metadata.put("validation_policy",validations.fingerprint(template.task()));
        String operation=switch(template.task()) {case "NEWS_SUMMARY"->"NEWS_SUMMARY";case "NEWS_DETAIL"->"NEWS_CLASSIFY";default->"NEWS_PARSE";};
        db.update("""
            INSERT INTO llm_runs(id,operation_type,provider,model_name,prompt_version,status,input_hash,request_metadata,
                response_metadata,created_at,prompt_template_id,task_code,news_article_id,request_payload)
            VALUES (?,?,'GEMINI',?,?,'RUNNING',?,?::jsonb,'{}'::jsonb,now(),?,?,?,?::jsonb)
            """,run,operation,model,template.version(),inputHash,metadata.toString(),template.id(),template.task(),article,request.toString());
        return new Outcome("CLAIMED",run,null,List.of());
    }
    @Transactional
    public boolean stageResponse(UUID run,LlmGateway.Reply reply,List<String> readiness,int latency) {
        JsonNode raw;
        try {raw=json.read(reply.rawBody());}catch(Exception e){raw=json.mapper().createObjectNode().put("raw_body",reply.rawBody());}
        return db.update("""
            UPDATE llm_runs SET status='PENDING_VALIDATION',response_payload=?::jsonb,response_text=?,http_status=?,
                input_tokens=?,output_tokens=?,latency_ms=?,model_name=coalesce(?,model_name),validation_errors=?::jsonb
            WHERE id=? AND status='RUNNING'
            """,raw.toString(),reply.outputText(),reply.httpStatus(),reply.inputTokens(),reply.outputTokens(),latency,
                reply.selectedModel(),json.mapper().valueToTree(readiness).toString(),run)==1;
    }
    @Transactional
    public Outcome finish(UUID run,UUID article,LlmPromptCatalog.Template template,NewsLlmContext.Context context,
                          String inputHash,LlmGateway.Reply reply,JsonNode output,List<String> validation,int latency) {
        lock(article,template.task());
        String state=db.queryForObject("SELECT status FROM llm_runs WHERE id=? FOR UPDATE",String.class,run);
        if("SUCCESS".equals(state)) {
            var existing=db.queryForList("SELECT id FROM llm_results WHERE llm_run_id=? AND is_current",UUID.class,run);
            if(!existing.isEmpty()) return new Outcome("CACHED",run,existing.getFirst(),List.of());
        }
        if(!Set.of("RUNNING","PENDING_VALIDATION").contains(state)) return new Outcome("LEASE_LOST",run,null,List.of("Run already completed or expired"));
        db.queryForList("SELECT id FROM news_articles WHERE id=? FOR SHARE",article);
        var now=contexts.build(article,template.task());
        var active=db.queryForList("SELECT id FROM llm_prompt_templates WHERE id=? AND enabled AND checksum=?",UUID.class,template.id(),template.checksum());
        String policy=db.queryForObject("SELECT request_metadata->>'validation_policy' FROM llm_runs WHERE id=?",String.class,run);
        var evaluation=validations.evaluate(run,template,context,output,validation,
                now.eligible()&&now.sourceHash().equals(context.sourceHash()),!active.isEmpty(),policy);
        var errors=evaluation.errors();
        String status=reply.httpStatus()<200||reply.httpStatus()>=300||evaluation.technicalFailure()?"FAILED":errors.isEmpty()?"SUCCESS":"REJECTED";
        JsonNode raw;
        try {raw=json.read(reply.rawBody());} catch(Exception e) {raw=json.mapper().createObjectNode().put("raw_body",reply.rawBody());}
        db.update("""
            UPDATE llm_runs SET status=?,response_payload=?::jsonb,response_text=?,validation_errors=?::jsonb,
                http_status=?,input_tokens=?,output_tokens=?,latency_ms=?,finished_at=now(),error_message=?,
                model_name=coalesce(?,model_name),validation_round_id=? WHERE id=?
            """,status,raw.toString(),reply.outputText(),json.mapper().valueToTree(errors).toString(),reply.httpStatus(),
                reply.inputTokens(),reply.outputTokens(),latency,errors.isEmpty()?null:String.join("; ",errors),reply.selectedModel(),evaluation.round(),run);
        if(!"SUCCESS".equals(status)) return new Outcome(status,run,null,List.copyOf(errors));
        UUID result=UUID.randomUUID();
        db.update("UPDATE llm_results SET is_current=false WHERE news_article_id=? AND task_code=? AND is_current",article,template.task());
        db.update("""
            INSERT INTO llm_results(id,llm_run_id,prompt_template_id,task_code,source_domain,news_article_id,
                source_hash,input_hash,schema_version,overview,result_json,quality_status,validation_round_id,validation_policy_hash)
            VALUES (?,?,?,?,'NEWS',?,?,?,?,?,?::jsonb,?,?,?)
            """,result,run,template.id(),template.task(),article,context.sourceHash(),inputHash,
                output.path("schema_version").asText(),output.path("overview").asText(),output.toString(),
                "SUFFICIENT".equals(output.path("status").asText())&&!evaluation.warnings()
                    && !Boolean.TRUE.equals(db.queryForObject("SELECT coalesce(metadata->>'recovery_assessment'='PARTIAL',false) FROM news_articles WHERE id=?",Boolean.class,article))
                    ?"VALID":"WARNING",evaluation.round(),policy);
        return new Outcome("SUCCESS",run,result,List.of());
    }
    @Transactional
    public Outcome fail(UUID run,String category,int latency) {
        db.update("UPDATE llm_runs SET status='FAILED',finished_at=now(),error_message=?,latency_ms=? WHERE id=? AND status='RUNNING'",category,latency,run);
        return new Outcome("FAILED",run,null,List.of(category));
    }
    public List<JsonNode> results(UUID article) {
        var ctx=contexts.build(article,"NEWS_DETAIL");
        if(!ctx.eligible()) return List.of();
        return db.query("""
            SELECT r.id,r.task_code,r.result_json,r.quality_status,r.created_at,r.validation_policy_hash FROM llm_results r
            JOIN llm_prompt_templates t ON t.id=r.prompt_template_id AND t.enabled
            WHERE r.news_article_id=? AND r.is_current AND r.source_hash=? AND r.validation_round_id IS NOT NULL
                AND EXISTS (SELECT 1 FROM validation_results v WHERE v.llm_run_id=r.llm_run_id
                    AND v.validation_round_id=r.validation_round_id)
                AND NOT EXISTS (SELECT 1 FROM validation_results v WHERE v.llm_run_id=r.llm_run_id
                    AND v.validation_round_id=r.validation_round_id AND v.result_status='FAIL' AND v.severity IN ('ERROR','CRITICAL'))
            ORDER BY r.task_code
            """,(rs,n)->{
                var result=json.mapper().createObjectNode();
                result.put("id",rs.getString(1)).put("task_code",rs.getString(2)).set("data",json.read(rs.getString(3)));
                result.put("quality_status",rs.getString(4)).put("created_at",rs.getTimestamp(5).toInstant().toString());
                try {if(!validations.fingerprint(rs.getString(2)).equals(rs.getString(6))) return null;}
                catch(IllegalStateException e) {return null;}
                return (JsonNode)result;
            },article,ctx.sourceHash()).stream().filter(Objects::nonNull).toList();
    }
    /** Recheck a stored response without another Gemini call or rewriting historical AI content. */
    @Transactional
    public Outcome revalidate(UUID run) {
        String taskCode = db.queryForObject("SELECT task_code FROM llm_runs WHERE id=?", String.class, run);
        if (taskCode != null && !LlmPromptCatalog.TASKS.contains(taskCode))
            throw new IllegalArgumentException("Use the task-specific validation/review API for this run");
        var rows=db.queryForList("SELECT * FROM llm_runs WHERE id=?",run);
        if(rows.isEmpty()) throw new IllegalArgumentException("Run not found");
        var row=rows.getFirst();
        if("RUNNING".equals(row.get("status"))) return new Outcome("RUNNING",run,null,List.of());
        UUID article=(UUID)row.get("news_article_id");String task=(String)row.get("task_code");
        if(article==null||task==null) return new Outcome("SKIPPED",run,null,List.of("LEGACY_RUN_WITHOUT_NEWS_CONTEXT"));
        lock(article,task);
        row=db.queryForList("SELECT * FROM llm_runs WHERE id=? FOR UPDATE",run).getFirst();
        if("RUNNING".equals(row.get("status"))) return new Outcome("RUNNING",run,null,List.of());
        var metadata=json.read(row.get("request_metadata").toString());
        if(!metadata.has("input")||!metadata.has("source_hash")) return new Outcome("SKIPPED",run,null,List.of("SOURCE_SNAPSHOT_MISSING"));
        var templates=db.query("SELECT * FROM llm_prompt_templates WHERE id=?",(rs,n)->new LlmPromptCatalog.Template(
            rs.getObject("id",UUID.class),task,rs.getString("version"),rs.getString("system_prompt"),
            json.read(rs.getString("request_schema")),json.read(rs.getString("response_schema")),rs.getString("checksum")),row.get("prompt_template_id"));
        if(templates.isEmpty()) return new Outcome("SKIPPED",run,null,List.of("TEMPLATE_MISSING"));
        var template=templates.getFirst();
        var context=new NewsLlmContext.Context(metadata.path("input"),metadata.path("source_hash").asText(),List.of());
        var current=contexts.build(article,task);var readiness=new ArrayList<String>();JsonNode output=null;
        Integer http=(Integer)row.get("http_status");String text=(String)row.get("response_text");
        if(http==null||http<200||http>=300) readiness.add("PROVIDER_HTTP_"+http);
        else if(text==null) readiness.add("PROVIDER_BLOCKED_OR_INCOMPLETE");
        else try {output=json.read(text);}catch(IllegalArgumentException e){readiness.add("RESPONSE_NOT_JSON");}
        if("PENDING_VALIDATION".equals(row.get("status"))) return finish(run,article,template,context,(String)row.get("input_hash"),
            new LlmGateway.Reply(http==null?0:http,row.get("response_payload").toString(),text,(Integer)row.get("input_tokens"),
                (Integer)row.get("output_tokens"),(String)row.get("model_name")),output,readiness,
            row.get("latency_ms")==null?0:((Number)row.get("latency_ms")).intValue());
        boolean active=db.queryForObject("SELECT count(*) FROM llm_prompt_templates WHERE id=? AND enabled AND checksum=?",
            Integer.class,template.id(),template.checksum())==1;
        String currentPolicy;
        try { currentPolicy=validations.fingerprint(task); }
        catch (IllegalStateException e) {
            return new Outcome("VALIDATION_CONFIGURATION_ERROR",run,null,List.of(e.getMessage()));
        }
        var evaluation=validations.evaluate(run,template,context,output,readiness,
            current.eligible()&&current.sourceHash().equals(context.sourceHash()),active,currentPolicy);
        var baseRequest=json.read(row.get("request_payload").toString()).deepCopy();
        var firstContent=baseRequest.path("contents").path(0).deepCopy();
        ((com.fasterxml.jackson.databind.node.ObjectNode)baseRequest).putArray("contents").add(firstContent);
        String validatedInputHash=hashes.sha256(template.checksum()+"\n"+metadata.path("routing_key").asText((String)row.get("model_name"))
            +"\n"+currentPolicy+"\n"+json.canonical(baseRequest));
        String status=evaluation.technicalFailure()?"FAILED":evaluation.errors().isEmpty()?"SUCCESS":"REJECTED";
        var published=db.queryForList("SELECT id FROM llm_results WHERE llm_run_id=? AND is_current",UUID.class,run);
        db.update("UPDATE llm_runs SET validation_round_id=? WHERE id=?",evaluation.round(),run);
        if(!published.isEmpty()) {
            if("SUCCESS".equals(status)) db.update("UPDATE llm_results SET validation_round_id=?,validation_policy_hash=?,input_hash=? WHERE llm_run_id=?",
                evaluation.round(),currentPolicy,validatedInputHash,run);
            else {
                db.update("UPDATE llm_results SET is_current=false WHERE llm_run_id=?",run);
                db.update("UPDATE llm_runs SET status=?,validation_errors=?::jsonb,error_message=? WHERE id=?",status,
                    json.mapper().valueToTree(evaluation.errors()).toString(),String.join("; ",evaluation.errors()),run);
            }
        } else if("SUCCESS".equals(status)) {
            // A corrected rule configuration may accept a previously rejected stored response.
            // Never reactivate an older historical result or replace another published result here.
            Integer existing=db.queryForObject("SELECT count(*) FROM llm_results WHERE llm_run_id=? OR (news_article_id=? AND task_code=? AND is_current)",
                Integer.class,run,article,task);
            if(existing==0) {
                UUID result=UUID.randomUUID();
                db.update("""
                    INSERT INTO llm_results(id,llm_run_id,prompt_template_id,task_code,source_domain,news_article_id,
                        source_hash,input_hash,schema_version,overview,result_json,quality_status,validation_round_id,validation_policy_hash)
                    VALUES (?,?,?,?,'NEWS',?,?,?,?,?,?::jsonb,?,?,?)
                    """,result,run,template.id(),task,article,context.sourceHash(),validatedInputHash,
                        output.path("schema_version").asText(),output.path("overview").asText(),output.toString(),
                        "SUFFICIENT".equals(output.path("status").asText())&&!evaluation.warnings()?"VALID":"WARNING",
                        evaluation.round(),currentPolicy);
                db.update("UPDATE llm_runs SET status='SUCCESS',validation_errors='[]'::jsonb,error_message=null WHERE id=?",run);
                return new Outcome("SUCCESS",run,result,List.of());
            }
        }
        return new Outcome(status,run,!"SUCCESS".equals(status)||published.isEmpty()?null:published.getFirst(),evaluation.errors());
    }
    @Transactional
    public void retireFinancial(UUID article) {
        lock(article,"NEWS_FINANCIAL_FACTS");
        db.update("UPDATE llm_results SET is_current=false WHERE news_article_id=? AND task_code='NEWS_FINANCIAL_FACTS' AND is_current",article);
    }
    public JsonNode run(UUID run) {
        var result=db.query("SELECT row_to_json(r)::text FROM llm_runs r WHERE id=?",(rs,n)->json.read(rs.getString(1)),run)
                .stream().findFirst().orElseThrow(()->new IllegalArgumentException("Run not found"));
        ((com.fasterxml.jackson.databind.node.ObjectNode)result).set("attempts",json.mapper().valueToTree(
                LlmAttemptAudit.read(db,json,run)));
        ((com.fasterxml.jackson.databind.node.ObjectNode)result).set("validations",json.mapper().valueToTree(
                db.query("SELECT row_to_json(v)::text FROM validation_results v WHERE llm_run_id=? ORDER BY checked_at,rule_code",
                        (rs,n)->json.read(rs.getString(1)),run)));
        return result;
    }
    @Transactional
    public void recordAttempt(UUID run,JsonNode request,LlmGateway.Attempt attempt) {
        LlmAttemptAudit.append(db,json,run,request,attempt);
    }
}
