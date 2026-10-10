package com.hethongdata.taichinh.service.llm;

import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;
import java.util.UUID;
import static org.assertj.core.api.Assertions.*;

class NewsLlmContractTests {
    final LlmJson json=new LlmJson(new ObjectMapper());
    final NewsLlmValidator validator=new NewsLlmValidator();
    LlmPromptCatalog.Template template(String task) throws Exception {
        try(var stream=new ClassPathResource("llm/"+task.toLowerCase()+".json").getInputStream()) {
            var r=json.mapper().readTree(stream);
            return new LlmPromptCatalog.Template(UUID.randomUUID(),task,"1",r.path("system_prompt").asText(),r.path("request_schema"),r.path("response_schema"),"test");
        }
    }
    ObjectNode input() {
        var root=json.mapper().createObjectNode();
        var article=root.putObject("article");
        article.put("id","3af81adf-571d-4d26-a714-cf8499c6831d");
        article.putArray("segments").addObject().put("id","p1").put("text","Dữ liệu kiểm thử riêng: doanh thu được công bố là 100 tỷ đồng.");
        article.putArray("companies").addObject().put("company_id","8a5c2276-7f51-49de-9ea6-ff2ebbbfa633");
        return root;
    }
    static ObjectNode output(LlmJson json,JsonNode input,String task) {
        var root=json.mapper().createObjectNode();
        root.put("schema_version","news.output.v1").put("task_code",task)
                .put("source_article_id",input.path("article").path("id").asText()).put("status","SUFFICIENT")
                .put("overview","Kết quả kiểm thử hợp đồng, không phải phân tích AI.");
        String quote=input.path("article").path("segments").path(0).path("text").asText();
        quote=quote.substring(0,Math.min(150,quote.length()));
        root.putArray("overview_evidence").addObject().put("segment_id","p1").put("quote",quote);
        var section=root.putArray("sections").addObject().put("type","KEY_FACT").put("heading","Kiểm thử")
                .put("content","Nội dung kiểm thử chỉ lưu trong schema kiểm thử.");
        section.putArray("evidence").addObject().put("segment_id","p1").put("quote",quote);
        root.putArray("limitations");
        if(task.equals("NEWS_DETAIL")) {
            root.putArray("document_types").add("OTHER");
            root.put("contains_financial_figures",false).put("contains_forecasts",false).putArray("company_impacts");
        }
        if(task.equals("NEWS_FINANCIAL_FACTS")) {
            root.putArray("facts");root.put("status","NOT_APPLICABLE");root.withArray("limitations").add("Không có số liệu phù hợp trong mẫu kiểm thử.");
        }
        return root;
    }
    @Test void validatesAllThreeContractsAndRejectsUnexpectedFields() throws Exception {
        for(String task:LlmPromptCatalog.TASKS) {
            var out=output(json,input(),task);
            assertThat(validator.response(template(task),input(),out)).isEmpty();
            out.put("invented_field",true);
            assertThat(validator.response(template(task),input(),out)).isNotEmpty();
        }
    }
    @Test void rejectsForgedEvidenceAndWrongArticle() throws Exception {
        var out=output(json,input(),"NEWS_SUMMARY");
        ((ObjectNode)out.path("overview_evidence").path(0)).put("quote","Trích dẫn này hoàn toàn không có trong bài");
        out.put("source_article_id",UUID.randomUUID().toString());
        assertThat(validator.response(template("NEWS_SUMMARY"),input(),out)).contains("EVIDENCE_QUOTE_MISMATCH","SOURCE_ARTICLE_MISMATCH");
    }
    @Test void rejectsUnknownCompanyAndUnsupportedFinancialValue() throws Exception {
        var out=output(json,input(),"NEWS_FINANCIAL_FACTS");
        out.put("status","SUFFICIENT");
        var fact=out.withArray("facts").addObject().put("metric_name","Doanh thu").put("company_id",UUID.randomUUID().toString())
                .put("value_text","9999").putNull("unit").putNull("period").putNull("attributed_to").put("fact_kind","ACTUAL");
        fact.set("evidence",out.path("overview_evidence").deepCopy());
        assertThat(validator.response(template("NEWS_FINANCIAL_FACTS"),input(),out)).contains("UNKNOWN_COMPANY","FINANCIAL_VALUE_NOT_IN_EVIDENCE");
    }
    @Test void strictJsonAndIncompleteResponsesAreRejected() throws Exception {
        assertThatThrownBy(()->json.read("{\"x\":1,\"x\":2}")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(()->json.read("{} {} ")).isInstanceOf(IllegalArgumentException.class);
        var out=output(json,input(),"NEWS_SUMMARY");out.remove("overview");
        assertThat(validator.response(template("NEWS_SUMMARY"),input(),out)).isNotEmpty();
    }
}
