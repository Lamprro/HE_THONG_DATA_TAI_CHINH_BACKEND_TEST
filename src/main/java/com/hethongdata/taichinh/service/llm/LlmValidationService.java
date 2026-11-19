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
    private final ChecksumService hashes;
    public LlmValidationService(JdbcTemplate db,LlmJson json,ChecksumService hashes) {
        this.db=db;this.json=json;this.hashes=hashes;
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
        try {schemaOk=ready&&LlmJson.validateSchema(template.responseSchema(),output).isEmpty();}
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
                        } else issues=executeNewsRule(executor,template,context.input(),output);
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

    // ---- NEWS response rules (executors referenced by validation_rules.executor_key) ----
    public static List<String> newsResponseErrors(LlmPromptCatalog.Template template,JsonNode input,JsonNode output) {
        var errors=new ArrayList<>(LlmJson.validateSchema(template.responseSchema(),output));
        if(!errors.isEmpty()) return errors;
        JsonNode article=input.path("article");
        if(!article.path("id").asText().equals(output.path("source_article_id").asText())) errors.add("SOURCE_ARTICLE_MISMATCH");
        Map<String,String> segments=new HashMap<>();
        article.path("segments").forEach(s->segments.put(s.path("id").asText(),s.path("text").asText()));
        Set<String> companies=new HashSet<>();
        article.path("companies").forEach(c->companies.add(c.path("company_id").asText()));
        inspect(output,segments,companies,errors);
        String status=output.path("status").asText();
        if("INSUFFICIENT".equals(status)) errors.add("INSUFFICIENT_CONTENT");
        if(("PARTIAL".equals(status)||"NOT_APPLICABLE".equals(status)) && output.path("limitations").isEmpty())
            errors.add("LIMITATIONS_REQUIRED");
        if(!"NEWS_FINANCIAL_FACTS".equals(template.task()) && output.path("sections").isEmpty()) errors.add("SECTIONS_REQUIRED");
        if("NEWS_FINANCIAL_FACTS".equals(template.task())) {
            if("NOT_APPLICABLE".equals(status) != output.path("facts").isEmpty()) errors.add("FACTS_STATUS_MISMATCH");
            output.path("facts").forEach(f->{
                StringBuilder quoted=new StringBuilder();
                f.path("evidence").forEach(e->quoted.append(e.path("quote").asText()).append('\n'));
                if(!quoted.toString().contains(f.path("value_text").asText())) errors.add("FINANCIAL_VALUE_NOT_IN_EVIDENCE");
                for(String key:List.of("unit","period","attributed_to"))
                    if(!f.path(key).isNull() && !quoted.toString().contains(f.path(key).asText())) errors.add(key.toUpperCase()+"_NOT_IN_EVIDENCE");
            });
        }
        Set<String> impactCompanies=new HashSet<>();
        output.path("company_impacts").forEach(i->{ if(!impactCompanies.add(i.path("company_id").asText())) errors.add("DUPLICATE_COMPANY_IMPACT"); });
        return List.copyOf(errors);
    }
    static List<String> executeNewsRule(String executor,LlmPromptCatalog.Template template,JsonNode input,JsonNode output) {
        if("LLM_RESPONSE_SCHEMA".equals(executor)) return LlmJson.validateSchema(template.responseSchema(),output);
        var all=newsResponseErrors(template,input,output);
        Set<String> names=switch(executor) {
            case "LLM_SOURCE_ID" -> Set.of("SOURCE_ARTICLE_MISMATCH");
            case "LLM_EVIDENCE_QUOTES" -> Set.of("EVIDENCE_QUOTE_MISMATCH");
            case "LLM_COMPANY_REFERENCES" -> Set.of("UNKNOWN_COMPANY");
            case "LLM_ANALYSIS_COMPLETENESS" -> Set.of("INSUFFICIENT_CONTENT","LIMITATIONS_REQUIRED","SECTIONS_REQUIRED");
            case "LLM_FINANCIAL_FACTS" -> Set.of("FACTS_STATUS_MISMATCH","FINANCIAL_VALUE_NOT_IN_EVIDENCE",
                    "UNIT_NOT_IN_EVIDENCE","PERIOD_NOT_IN_EVIDENCE","ATTRIBUTED_TO_NOT_IN_EVIDENCE");
            case "LLM_COMPANY_IMPACT_UNIQUE" -> Set.of("DUPLICATE_COMPANY_IMPACT");
            default -> throw new IllegalArgumentException("Unknown LLM executor: "+executor);
        };
        return all.stream().filter(names::contains).distinct().toList();
    }
    private static void inspect(JsonNode node,Map<String,String> segments,Set<String> companies,List<String> errors) {
        if(node.isObject()) {
            if(node.has("segment_id")) {
                String body=segments.get(node.path("segment_id").asText());
                if(body==null || !body.contains(node.path("quote").asText())) errors.add("EVIDENCE_QUOTE_MISMATCH");
            }
            if(node.hasNonNull("company_id") && !companies.contains(node.path("company_id").asText())) errors.add("UNKNOWN_COMPANY");
        }
        node.forEach(child->{ if(child.isContainerNode()) inspect(child,segments,companies,errors); });
    }
}
