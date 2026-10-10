package com.hethongdata.taichinh.service.llm;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Component;

@Component
public class LlmJson {
    private final ObjectMapper mapper;
    public LlmJson(ObjectMapper mapper) {
        this.mapper = mapper.copy()
                .enable(com.fasterxml.jackson.core.JsonParser.Feature.STRICT_DUPLICATE_DETECTION)
                .enable(com.fasterxml.jackson.databind.DeserializationFeature.FAIL_ON_TRAILING_TOKENS);
    }
    public JsonNode read(String value) {
        try {
            JsonNode result=mapper.readTree(value);
            if(result==null || result.isNull()) throw new IllegalArgumentException("JSON must not be empty or null");
            return result;
        }
        catch (Exception e) { throw new IllegalArgumentException("Invalid JSON", e); }
    }
    public ObjectMapper mapper() { return mapper; }
    /** JSONB does not preserve object key order; identities must not depend on it. */
    public JsonNode canonical(JsonNode value) {
        if(value.isObject()) {
            var result=mapper.createObjectNode();
            var names=new java.util.TreeSet<String>();value.fieldNames().forEachRemaining(names::add);
            for(String name:names) result.set(name,canonical(value.get(name)));
            return result;
        }
        if(value.isArray()) {
            var result=mapper.createArrayNode();for(var item:value) result.add(canonical(item));return result;
        }
        return value;
    }
}
