package com.hethongdata.taichinh.service.llm;

import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.stereotype.Component;
import java.util.*;

@Component
public class NewsLlmValidator {
    public List<String> schema(JsonNode schema,JsonNode value) {
        return LlmSchemaValidator.validate(schema,value);
    }
    public List<String> response(LlmPromptCatalog.Template template,JsonNode input,JsonNode output) {
        var errors=new ArrayList<>(schema(template.responseSchema(),output));
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
    public List<String> execute(String executor,LlmPromptCatalog.Template template,JsonNode input,JsonNode output) {
        if("LLM_RESPONSE_SCHEMA".equals(executor)) return schema(template.responseSchema(),output);
        var all=response(template,input,output);
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
    private void inspect(JsonNode node,Map<String,String> segments,Set<String> companies,List<String> errors) {
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
