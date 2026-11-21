package com.hethongdata.taichinh.service.llm;

import com.fasterxml.jackson.databind.JsonNode;
import com.hethongdata.taichinh.repository.jdbc.llm.LlmRunJdbcRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.util.*;

@Service
public class LlmRunStore {
    public record Outcome(String status,UUID runId,UUID resultId,List<String> issues) {}
    private final LlmRunJdbcRepository repo;
    private final LlmJson json;
    private final NewsLlmContext contexts;
    private final LlmValidationService validations;
    private final com.hethongdata.taichinh.service.ingestion.ChecksumService hashes;
    public LlmRunStore(LlmRunJdbcRepository repo,LlmJson json,NewsLlmContext contexts,LlmValidationService validations,
            com.hethongdata.taichinh.service.ingestion.ChecksumService hashes) {
        this.repo=repo;this.json=json;this.contexts=contexts;this.validations=validations;
        this.hashes=hashes;
    }
    private void lock(UUID article,String task) {
        repo.lock(article, task);
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
        var cached=repo.findCachedResult(article,template.task(),inputHash);
        if(!cached.isEmpty()) return new Outcome("CACHED",(UUID)cached.getFirst().get("llm_run_id"),(UUID)cached.getFirst().get("id"),List.of());
        repo.expireRunningLeases(article,template.task());
        var running=repo.findActiveRuns(article,template.task());
        if(!running.isEmpty()) return new Outcome("RUNNING",running.getFirst(),null,List.of());
        Integer attempts=repo.countFailedAttempts(article,template.task(),inputHash);
        if(attempts!=null && attempts>=3) return new Outcome("RETRY_LIMIT",null,null,List.of("Three failed attempts for unchanged input/model/template"));
        UUID run=UUID.randomUUID();
        var metadata=json.mapper().createObjectNode();
        metadata.set("input",context.input());
        metadata.put("source_hash",context.sourceHash()).put("template_checksum",template.checksum()).put("routing_key",routingKey);
        metadata.put("validation_policy",validations.fingerprint(template.task()));
        String operation=switch(template.task()) {case "NEWS_SUMMARY"->"NEWS_SUMMARY";case "NEWS_DETAIL"->"NEWS_CLASSIFY";default->"NEWS_PARSE";};
        repo.insertRunningRun(run,operation,model,template.version(),inputHash,metadata.toString(),template.id(),template.task(),article,request.toString());
        return new Outcome("CLAIMED",run,null,List.of());
    }
    @Transactional
    public boolean stageResponse(UUID run,LlmGateway.Reply reply,List<String> readiness,int latency) {
        JsonNode raw;
        try {raw=json.read(reply.rawBody());}catch(Exception e){raw=json.mapper().createObjectNode().put("raw_body",reply.rawBody());}
        return repo.stageResponse(run,raw.toString(),reply.outputText(),reply.httpStatus(),reply.inputTokens(),
                reply.outputTokens(),latency,reply.selectedModel(),json.mapper().valueToTree(readiness).toString());
    }
    @Transactional
    public Outcome finish(UUID run,UUID article,LlmPromptCatalog.Template template,NewsLlmContext.Context context,
                          String inputHash,LlmGateway.Reply reply,JsonNode output,List<String> validation,int latency) {
        lock(article,template.task());
        String state=repo.findStatusForUpdate(run);
        if("SUCCESS".equals(state)) {
            var existing=repo.findCurrentResultIdsByRunId(run);
            if(!existing.isEmpty()) return new Outcome("CACHED",run,existing.getFirst(),List.of());
        }
        if(!Set.of("RUNNING","PENDING_VALIDATION").contains(state)) return new Outcome("LEASE_LOST",run,null,List.of("Run already completed or expired"));
        repo.lockNewsArticleForShare(article);
        var now=contexts.build(article,template.task());
        boolean active=repo.isTemplateActive(template.id(),template.checksum());
        String policy=repo.findValidationPolicy(run);
        var evaluation=validations.evaluate(run,template,context,output,validation,
                now.eligible()&&now.sourceHash().equals(context.sourceHash()),active,policy);
        var errors=evaluation.errors();
        String status=reply.httpStatus()<200||reply.httpStatus()>=300||evaluation.technicalFailure()?"FAILED":errors.isEmpty()?"SUCCESS":"REJECTED";
        JsonNode raw;
        try {raw=json.read(reply.rawBody());} catch(Exception e) {raw=json.mapper().createObjectNode().put("raw_body",reply.rawBody());}
        repo.updateRunFinished(run,status,raw.toString(),reply.outputText(),json.mapper().valueToTree(errors).toString(),
                reply.httpStatus(),reply.inputTokens(),reply.outputTokens(),latency,errors.isEmpty()?null:String.join("; ",errors),
                reply.selectedModel(),evaluation.round());
        if(!"SUCCESS".equals(status)) return new Outcome(status,run,null,List.copyOf(errors));
        UUID result=UUID.randomUUID();
        repo.retireResults(article,template.task());
        boolean partialRecovery=repo.isArticleRecoveryAssessmentPartial(article);
        repo.insertResult(result,run,template.id(),template.task(),article,context.sourceHash(),inputHash,
                output.path("schema_version").asText(),output.path("overview").asText(),output.toString(),
                "SUFFICIENT".equals(output.path("status").asText())&&!evaluation.warnings()&&!partialRecovery?"VALID":"WARNING",
                evaluation.round(),policy);
        return new Outcome("SUCCESS",run,result,List.of());
    }
    @Transactional
    public Outcome fail(UUID run,String category,int latency) {
        repo.failRun(run,category,latency);
        return new Outcome("FAILED",run,null,List.of(category));
    }
    public List<JsonNode> results(UUID article) {
        var ctx=contexts.build(article,"NEWS_DETAIL");
        if(!ctx.eligible()) return List.of();
        return repo.findPassingResults(article,ctx.sourceHash()).stream().map(row->{
            var result=json.mapper().createObjectNode();
            result.put("id",row.get("id").toString()).put("task_code",(String)row.get("task_code"))
                    .set("data",json.read((String)row.get("result_json")));
            result.put("quality_status",(String)row.get("quality_status"))
                    .put("created_at",((java.sql.Timestamp)row.get("created_at")).toInstant().toString());
            try {if(!validations.fingerprint((String)row.get("task_code")).equals(row.get("validation_policy_hash"))) return null;}
            catch(IllegalStateException e) {return null;}
            return (JsonNode)result;
        }).filter(Objects::nonNull).toList();
    }
    /** Recheck a stored response without another Gemini call or rewriting historical AI content. */
    @Transactional
    public Outcome revalidate(UUID run) {
        String taskCode = repo.findTaskCode(run);
        if (taskCode != null && !LlmPromptCatalog.TASKS.contains(taskCode))
            throw new IllegalArgumentException("Use the task-specific validation/review API for this run");
        var rows=repo.findRunById(run);
        if(rows.isEmpty()) throw new IllegalArgumentException("Run not found");
        var row=rows.getFirst();
        if("RUNNING".equals(row.get("status"))) return new Outcome("RUNNING",run,null,List.of());
        UUID article=(UUID)row.get("news_article_id");String task=(String)row.get("task_code");
        if(article==null||task==null) return new Outcome("SKIPPED",run,null,List.of("LEGACY_RUN_WITHOUT_NEWS_CONTEXT"));
        lock(article,task);
        row=repo.findRunByIdForUpdate(run).getFirst();
        if("RUNNING".equals(row.get("status"))) return new Outcome("RUNNING",run,null,List.of());
        var metadata=json.read(row.get("request_metadata").toString());
        if(!metadata.has("input")||!metadata.has("source_hash")) return new Outcome("SKIPPED",run,null,List.of("SOURCE_SNAPSHOT_MISSING"));
        var templates=repo.findTemplateById((UUID)row.get("prompt_template_id"),task,json);
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
        boolean active=repo.countActiveTemplate(template.id(),template.checksum());
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
        var published=repo.findCurrentResultIdsByRunId(run);
        repo.updateRunValidationRound(run,evaluation.round());
        if(!published.isEmpty()) {
            if("SUCCESS".equals(status)) repo.updateResultOnSuccess(run,evaluation.round(),currentPolicy,validatedInputHash);
            else repo.demoteResultAndFailRun(run,status,json.mapper().valueToTree(evaluation.errors()).toString(),
                    String.join("; ",evaluation.errors()));
        } else if("SUCCESS".equals(status)) {
            // A corrected rule configuration may accept a previously rejected stored response.
            // Never reactivate an older historical result or replace another published result here.
            Integer existing=repo.countExistingResultsForArticle(run,article,task);
            if(existing!=null && existing==0) {
                UUID result=UUID.randomUUID();
                repo.insertResult(result,run,template.id(),task,article,context.sourceHash(),validatedInputHash,
                        output.path("schema_version").asText(),output.path("overview").asText(),output.toString(),
                        "SUFFICIENT".equals(output.path("status").asText())&&!evaluation.warnings()?"VALID":"WARNING",
                        evaluation.round(),currentPolicy);
                repo.updateRunValidationSuccess(run);
                return new Outcome("SUCCESS",run,result,List.of());
            }
        }
        return new Outcome(status,run,!"SUCCESS".equals(status)||published.isEmpty()?null:published.getFirst(),evaluation.errors());
    }
    @Transactional
    public void retireFinancial(UUID article) {
        lock(article,"NEWS_FINANCIAL_FACTS");
        repo.retireResults(article,"NEWS_FINANCIAL_FACTS");
    }
    public JsonNode run(UUID run) {
        var raw=repo.findRunJson(run).orElseThrow(()->new IllegalArgumentException("Run not found"));
        var result=json.read(raw);
        ((com.fasterxml.jackson.databind.node.ObjectNode)result).set("attempts",json.mapper().valueToTree(
                repo.readAttempts(json,run)));
        var validationsList=new ArrayList<JsonNode>();
        for(String v:repo.findValidationResultsJson(run)) validationsList.add(json.read(v));
        ((com.fasterxml.jackson.databind.node.ObjectNode)result).set("validations",json.mapper().valueToTree(validationsList));
        return result;
    }
    @Transactional
    public void recordAttempt(UUID run,JsonNode request,LlmGateway.Attempt attempt) {
        repo.appendAttempt(json,run,request,attempt);
    }
}
