package com.hethongdata.taichinh.service.llm;

import com.fasterxml.jackson.databind.JsonNode;

public interface LlmGateway {
    record Reply(int httpStatus,String rawBody,String outputText,Integer inputTokens,Integer outputTokens,String selectedModel) {
        public Reply(int httpStatus,String rawBody,String outputText,Integer inputTokens,Integer outputTokens) {
            this(httpStatus,rawBody,outputText,inputTokens,outputTokens,null);
        }
    }
    record Attempt(int number,String model,Integer httpStatus,String error,String rawBody,String outputText,
                   Integer inputTokens,Integer outputTokens,int latencyMs) {}
    boolean configured();
    String model();
    default String routingKey() { return model(); }
    JsonNode request(LlmPromptCatalog.Template template,JsonNode input);
    Reply call(JsonNode request) throws Exception;
    default Reply call(JsonNode request,java.util.function.Consumer<Attempt> audit) throws Exception {return call(request);}
}
