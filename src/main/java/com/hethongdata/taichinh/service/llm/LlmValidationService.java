package com.hethongdata.taichinh.service.llm;

import com.fasterxml.jackson.databind.JsonNode;
import com.hethongdata.taichinh.service.ingestion.ChecksumService;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import java.util.*;

/** Shared DB rule catalogue and per-rule audit, executed inside the result publication transaction. */
@Service
public class LlmValidationService {
    public record Evaluation(UUID round,List<String> errors,boolean technicalFailure,boolean warnings) {}
    private static final Set<String> COMMON=Set.of("LLM_RESPONSE_READY","LLM_RESPONSE_SCHEMA","LLM_SOURCE_ID",
            "LLM_EVIDENCE_QUOTES","LLM_COMPANY_REFERENCES","LLM_ANALYSIS_COMPLETENESS",
            "LLM_COMPANY_IMPACT_UNIQUE","LLM_SOURCE_SNAPSHOT","LLM_PROMPT_SNAPSHOT");
    private final JdbcTemplate db;
    private final LlmJson json;
    private final NewsLlmValidator validator;
    private final ChecksumService hashes;
    public LlmValidationService(JdbcTemplate db,LlmJson json,NewsLlmValidator validator,ChecksumService hashes) {
        this.db=db;this.json=json;this.validator=validator;this.hashes=hashes;
    }
    private List<JsonNode> rules(String task) {
        return db.query("""
            SELECT jsonb_build_object('id',id,'code',code,'executor',executor_key,'severity',severity,
                'config',rule_config,'domain',data_domain)::text
            FROM validation_rules WHERE data_domain='LLM_OUTPUT' AND is_active ORDER BY code
            """,(rs,n)->json.read(rs.getString(1))).stream().filter(r->{
                var tasks=r.path("config").path("tasks");
                if(!tasks.isArray()||!r.path("config").hasNonNull("version"))
                    throw new IllegalStateException("INVALID_LLM_RULE_CONFIG: "+r.path("code").asText());
                for(var t:tasks) if(task.equals(t.asText())) return true;
                return false;
            }).toList();
    }
    private void ensureComplete(List<JsonNode> rules,String task) {
        var required=new HashSet<>(COMMON);if("NEWS_FINANCIAL_FACTS".equals(task)) required.add("LLM_FINANCIAL_FACTS");
        for(var rule:rules) {
            String executor=rule.path("executor").asText();
            if(!COMMON.contains(executor)&&!"LLM_FINANCIAL_FACTS".equals(executor))
                throw new IllegalStateException("UNKNOWN_LLM_EXECUTOR: "+executor);
            if(!Set.of("ERROR","CRITICAL","WARNING").contains(rule.path("severity").asText()))
                throw new IllegalStateException("INVALID_LLM_RULE_SEVERITY");
            if(rule.path("code").asText().equals(executor)&&!"WARNING".equals(rule.path("severity").asText())) required.remove(executor);
        }
        if(!required.isEmpty()) throw new IllegalStateException("MISSING_BLOCKING_LLM_RULES: "+new TreeSet<>(required));
    }
    public String fingerprint(String task) {
        var rules=rules(task);ensureComplete(rules,task);return fingerprint(rules);
    }
    private String fingerprint(List<JsonNode> rules) {return hashes.sha256(json.mapper().valueToTree(rules).toString());}
    public Evaluation evaluate(UUID run,LlmPromptCatalog.Template template,NewsLlmContext.Context context,
            JsonNode output,List<String> readiness,boolean sourceCurrent,boolean promptCurrent,String expectedPolicy) {
        UUID round=UUID.randomUUID();var errors=new ArrayList<String>();boolean technical=false,warnings=false;
        List<JsonNode> rules;
        try {rules=rules(template.task());} catch(Exception e) {return new Evaluation(round,List.of(e.getMessage()),true,false);}
        try {ensureComplete(rules,template.task());} catch(Exception e) {errors.add(e.getMessage());technical=true;}
        boolean policyCurrent=fingerprint(rules).equals(expectedPolicy);
        boolean ready=readiness.isEmpty()&&output!=null;
        boolean schemaOk=false;
        try {schemaOk=ready&&validator.schema(template.responseSchema(),output).isEmpty();}
        catch(Exception e) {errors.add("LLM_SCHEMA_EXECUTOR_ERROR");technical=true;}
        for(var rule:rules) {
            String executor=rule.path("executor").asText(),state="PASS",message="Rule passed";
            List<String> issues=List.of();
            try {
                switch(executor) {
                    case "LLM_RESPONSE_READY" -> issues=ready?List.of():readiness.isEmpty()?List.of("RESPONSE_MISSING"):readiness;
                    case "LLM_SOURCE_SNAPSHOT" -> issues=sourceCurrent?List.of():List.of("SOURCE_CHANGED_DURING_RUN");
                    case "LLM_PROMPT_SNAPSHOT" -> {
                        var problems=new ArrayList<String>();
                        if(!promptCurrent) problems.add("TEMPLATE_CHANGED_DURING_RUN");
                        if(!policyCurrent) problems.add("VALIDATION_RULES_CHANGED_DURING_RUN");
                        issues=problems;
                    }
                    default -> {
                        if(!ready||(!schemaOk&&!"LLM_RESPONSE_SCHEMA".equals(executor))) {
                            state="SKIP";message="Prerequisite response/schema failed; publication remains blocked";
                        } else issues=validator.execute(executor,template,context.input(),output);
                    }
                }
            } catch(Exception e) {issues=List.of("LLM_RULE_EXECUTOR_ERROR: "+executor);technical=true;}
            if(!issues.isEmpty()) {
                state="FAIL";message=String.join("; ",issues);
                if("WARNING".equals(rule.path("severity").asText())) warnings=true;else errors.addAll(issues);
            }
            db.update("""
                INSERT INTO validation_results(id,validation_rule_id,rule_code,severity,result_status,handling_status,
                    checked_at,validation_target,llm_run_id,validation_round_id,rule_snapshot,observed_value,expected_value,message)
                VALUES (?,?,?,?,?,?,now(),'LLM_OUTPUT',?,?,?::jsonb,?,?,?)
                """,UUID.randomUUID(),rule.path("id").asLong(),rule.path("code").asText(),rule.path("severity").asText(),
                    state,"FAIL".equals(state)?"OPEN":"NOT_REQUIRED",run,round,rule.toString(),
                    json.mapper().valueToTree(issues).toString(),"PASS",message);
        }
        return new Evaluation(round,List.copyOf(new LinkedHashSet<>(errors)),technical,warnings);
    }
}
