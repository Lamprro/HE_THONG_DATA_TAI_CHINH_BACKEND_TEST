package com.hethongdata.taichinh.service.llm;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.*;
import java.net.*;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.Executors;
import static org.junit.jupiter.api.Assertions.*;

class GeminiRoutingTests {
    HttpServer server;
    java.util.concurrent.ExecutorService executor;
    List<String> paths=Collections.synchronizedList(new ArrayList<>());
    LlmJson json=new LlmJson(new ObjectMapper());
    GeminiRoutingProperties policy;
    static final String OK="{\"candidates\":[{\"finishReason\":\"STOP\",\"content\":{\"parts\":[{\"text\":\"{}\"}]}}],\"usageMetadata\":{\"promptTokenCount\":5,\"candidatesTokenCount\":2}}";
    @BeforeEach void start() throws Exception {
        server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
        executor=Executors.newCachedThreadPool();server.setExecutor(executor);
        policy=new GeminiRoutingProperties();policy.setModels("gemini-a,gemini-b,gemini-c");
        policy.setBackoffMs(0);policy.setAttemptTimeoutMs(1000);policy.setTotalTimeoutMs(3000);
    }
    @AfterEach void stop(){server.stop(0);executor.shutdownNow();}
    void responses(int... codes) {
        server.createContext("/",exchange->{
            int index; synchronized(paths){index=paths.size();paths.add(exchange.getRequestURI().getPath());}
            assertEquals("test-key",exchange.getRequestHeaders().getFirst("x-goog-api-key"));
            exchange.getRequestBody().readAllBytes();
            int code=codes[Math.min(index,codes.length-1)];
            if(code==429) exchange.getResponseHeaders().set("Retry-After","30");
            byte[] bytes=(code==200?OK:"{\"error\":\"test failure\"}").getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(code,bytes.length);exchange.getResponseBody().write(bytes);exchange.close();
        });server.start();
    }
    GeminiLlmGateway gateway(){return new GeminiLlmGateway(json,"test-key","",true,policy,HttpClient.newHttpClient(),URI.create("http://127.0.0.1:"+server.getAddress().getPort()+"/"));}
    @Test void rateLimitAndServerFailureSwitchModelsAndAudit() throws Exception {
        responses(429,503,200);var audit=new ArrayList<LlmGateway.Attempt>();
        var result=gateway().call(json.read("{}"),audit::add);
        assertEquals("gemini-c",result.selectedModel());assertEquals("{}",result.outputText());
        assertEquals(List.of(429,503,200),audit.stream().map(LlmGateway.Attempt::httpStatus).toList());
        assertEquals(List.of("gemini-a","gemini-b","gemini-c"),audit.stream().map(LlmGateway.Attempt::model).toList());
        assertEquals(5,result.inputTokens());assertEquals(3,paths.size());
    }
    @Test void missingModelFallsBack() throws Exception {responses(404,200);assertEquals("gemini-b",gateway().call(json.read("{}")).selectedModel());}
    @Test void invalidKeyDoesNotTryOtherModels() throws Exception {responses(403,200);assertEquals(403,gateway().call(json.read("{}")).httpStatus());assertEquals(1,paths.size());}
    @Test void invalidRequestDoesNotTryOtherModels() throws Exception {responses(400,200);assertEquals(400,gateway().call(json.read("{}")).httpStatus());assertEquals(1,paths.size());}
    @Test void unauthorizedDoesNotTryOtherModels() throws Exception {responses(401,200);assertEquals(401,gateway().call(json.read("{}")).httpStatus());assertEquals(1,paths.size());}
    @Test void maximumAttemptsIsRespected() throws Exception {responses(503);policy.setMaxAttempts(2);assertEquals(503,gateway().call(json.read("{}")).httpStatus());assertEquals(2,paths.size());}
    @Test void rateLimitedModelIsSkippedOnNextCall() throws Exception {
        responses(429,200);var gateway=gateway();gateway.call(json.read("{}"));
        assertEquals("gemini-b",gateway.call(json.read("{}")).selectedModel());
        assertEquals(List.of("/models/gemini-a:generateContent","/models/gemini-b:generateContent","/models/gemini-b:generateContent"),paths);
    }
    @Test void timeoutFallsBack() throws Exception {
        policy.setAttemptTimeoutMs(150);
        server.createContext("/",exchange->{
            var path=exchange.getRequestURI().getPath();paths.add(path);
            if(path.contains("gemini-a")) try{Thread.sleep(500);}catch(InterruptedException ignored){Thread.currentThread().interrupt();}
            try {var bytes=OK.getBytes(StandardCharsets.UTF_8);exchange.sendResponseHeaders(200,bytes.length);exchange.getResponseBody().write(bytes);}finally{exchange.close();}
        });server.start();
        var audit=new ArrayList<LlmGateway.Attempt>();var result=gateway().call(json.read("{}"),audit::add);
        assertEquals("gemini-b",result.selectedModel());assertEquals("TIMEOUT",audit.getFirst().error());
    }
    @Test void safetyDecisionIsNotRetried() throws Exception {
        server.createContext("/",exchange->{paths.add(exchange.getRequestURI().getPath());byte[] body="{\"candidates\":[{\"finishReason\":\"SAFETY\"}]}".getBytes(StandardCharsets.UTF_8);exchange.sendResponseHeaders(200,body.length);exchange.getResponseBody().write(body);exchange.close();});server.start();
        assertNull(gateway().call(json.read("{}")).outputText());assertEquals(1,paths.size());
    }
    @Test void modelsDeduplicateWithoutReordering(){policy.setModels("gemini-a,gemini-a, gemini-b");assertEquals(List.of("gemini-a","gemini-b"),policy.orderedModels(""));}
    @Test void providerSchemaRemovesNestedBoundsButKeepsTypedConstants() throws Exception {
        var template=new NewsLlmContractTests().template("NEWS_SUMMARY");
        var schema=gateway().request(template,json.read("{}")).path("generationConfig").path("responseJsonSchema");
        assertEquals("string",schema.path("properties").path("task_code").path("type").asText());
        assertFalse(schema.toString().contains("maxItems"));
        assertFalse(schema.toString().contains("minItems"));
        assertTrue(template.responseSchema().toString().contains("maxItems"));
    }
    @Test void totalDeadlineStopsFurtherAttempts() throws Exception {
        policy.setTotalTimeoutMs(120);policy.setAttemptTimeoutMs(1000);
        server.createContext("/",exchange->{paths.add(exchange.getRequestURI().getPath());try{Thread.sleep(600);}catch(InterruptedException ignored){Thread.currentThread().interrupt();}finally{exchange.close();}});server.start();
        var audit=new ArrayList<LlmGateway.Attempt>();long start=System.nanoTime();
        assertThrows(java.io.IOException.class,()->gateway().call(json.read("{}"),audit::add));
        assertTrue((System.nanoTime()-start)/1_000_000<1500);
        assertEquals(1,audit.size());assertEquals("TIMEOUT",audit.getFirst().error());
    }
    @Test void retryAfterIsBounded(){assertEquals(30000,GeminiLlmGateway.retryAfterMillis("30"));assertEquals(86400000,GeminiLlmGateway.retryAfterMillis("999999"));assertEquals(0,GeminiLlmGateway.retryAfterMillis("invalid"));}
    @Test void segmentsPreserveTextAndAvoidCuttingNormalSentences(){
        String sentence="Doanh nghiệp công bố kết quả kinh doanh tăng trưởng so với cùng kỳ. ";
        String body=sentence.repeat(90);var segments=NewsLlmContext.segmentText(body);
        assertEquals(body,String.join("",segments));assertTrue(segments.size()>1);
        segments.forEach(s->{assertTrue(s.length()<=1800);assertTrue(s.endsWith(". "));});
    }
    @Test void longUnbrokenTextIsNotLost(){String body="x".repeat(9000);var segments=NewsLlmContext.segmentText(body);assertEquals(body,String.join("",segments));segments.forEach(s->assertTrue(s.length()<=1800));}
}
