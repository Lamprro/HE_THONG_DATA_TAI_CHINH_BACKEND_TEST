package com.hethongdata.taichinh.service.llm;

import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import java.net.URI;
import java.net.http.*;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;
import java.io.IOException;

@Component
public class GeminiLlmGateway implements LlmGateway {
    private final LlmJson json;
    private final String key;
    private final boolean enabled;
    private final HttpClient client;
    private final GeminiRoutingProperties policy;
    private final List<String> models;
    private final URI base;
    private final Map<String,Long> cooldowns=new ConcurrentHashMap<>();
    @org.springframework.beans.factory.annotation.Autowired
    public GeminiLlmGateway(LlmJson json,@Value("${financial.llm.gemini.api-key:}") String key,
            @Value("${financial.llm.gemini.model:}") String model,@Value("${financial.llm.enabled:false}") boolean enabled,
            GeminiRoutingProperties policy) {
        this(json,key,model,enabled,policy,HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build(),
                URI.create("https://generativelanguage.googleapis.com/v1beta/"));
    }
    public GeminiLlmGateway(LlmJson json,String key,String model,boolean enabled) {
        this(json,key,model,enabled,new GeminiRoutingProperties());
    }
    GeminiLlmGateway(LlmJson json,String key,String model,boolean enabled,GeminiRoutingProperties policy,HttpClient client,URI base) {
        this.json=json;this.key=key;this.enabled=enabled;this.policy=policy;this.client=client;this.base=base;
        this.models=policy.orderedModels(model);
    }
    public boolean configured() { return enabled && !key.isBlank(); }
    public String model() { return models.getFirst(); }
    public String routingKey() {return "route:"+new com.hethongdata.taichinh.service.ingestion.ChecksumService().sha256(String.join(",",models));}
    public Map<String,Object> configuration() {
        var result=new LinkedHashMap<String,Object>(Map.of("enabled",enabled,"key_present",!key.isBlank(),"models",models,"routing_key",routingKey(),
                "max_attempts",policy.getMaxAttempts(),"max_attempts_per_model",policy.getMaxAttemptsPerModel(),
                "attempt_timeout_ms",policy.getAttemptTimeoutMs(),"total_timeout_ms",policy.getTotalTimeoutMs(),
                "cooldowns_until_epoch_ms",Map.copyOf(cooldowns)));
        result.put("max_output_tokens",policy.getMaxOutputTokens());
        result.put("thinking_budget",policy.getThinkingBudget());
        return result;
    }
    public JsonNode availableModels() throws Exception {
        if(key.isBlank()) throw new IllegalArgumentException("GEMINI_KEY_MISSING");
        var result=json.mapper().createObjectNode();var found=result.putArray("models");
        String page="";
        for(int i=0;i<10;i++) {
            var request=HttpRequest.newBuilder(base.resolve("models?pageSize=1000"+page)).timeout(Duration.ofSeconds(20))
                    .header("x-goog-api-key",key).GET().build();
            var response=client.send(request,HttpResponse.BodyHandlers.ofString());
            result.put("http_status",response.statusCode());
            if(response.statusCode()!=200) {result.put("error","MODEL_DISCOVERY_HTTP_"+response.statusCode());return result;}
            var body=json.read(response.body());
            body.path("models").forEach(m->{
                String id=m.path("name").asText().replaceFirst("^models/","");
                if(models.contains(id)) found.addObject().put("id",id).put("display_name",m.path("displayName").asText())
                        .set("supported_methods",m.path("supportedGenerationMethods"));
            });
            if(!body.hasNonNull("nextPageToken")) break;
            page="&pageToken="+java.net.URLEncoder.encode(body.path("nextPageToken").asText(),java.nio.charset.StandardCharsets.UTF_8);
        }
        return result;
    }
    public JsonNode request(LlmPromptCatalog.Template template,JsonNode input) {
        var root=json.mapper().createObjectNode();
        root.putObject("systemInstruction").putArray("parts").addObject().put("text",template.prompt());
        root.putArray("contents").addObject().put("role","user").putArray("parts").addObject().put("text",input.toString());
        var config=root.putObject("generationConfig");
        config.put("responseMimeType","application/json").put("temperature",0.1).put("maxOutputTokens",policy.getMaxOutputTokens());
        config.putObject("thinkingConfig").put("thinkingBudget",policy.getThinkingBudget());
        config.set("responseJsonSchema",providerSchema(template.responseSchema()));
        return root;
    }
    // Gemini supports a subset. The complete authoritative schema is still enforced by Java.
    private JsonNode providerSchema(JsonNode source) {
        var target=json.mapper().createObjectNode();
        // Nested array bounds in these news schemas trigger INVALID_ARGUMENT on live Gemini.
        // Keep the structural schema here; the authoritative bounds remain enforced by Java.
        for(String key:java.util.List.of("type","required","enum","additionalProperties","minimum","maximum","description"))
            if(source.has(key)) target.set(key,source.get(key).deepCopy());
        if(source.has("const")) {
            target.putArray("enum").add(source.get("const"));
            if(!target.has("type")) target.put("type",source.get("const").isTextual()?"string":source.get("const").isBoolean()?"boolean":"number");
        }
        if(source.has("properties")) {
            var properties=target.putObject("properties");
            source.get("properties").fields().forEachRemaining(e->properties.set(e.getKey(),providerSchema(e.getValue())));
        }
        if(source.has("items")) target.set("items",providerSchema(source.get("items")));
        return target;
    }
    public Reply call(JsonNode request) throws Exception {
        return call(request,attempt->{});
    }
    @Override public Reply call(JsonNode request,Consumer<Attempt> audit) throws Exception {
        if(!configured()) throw new IllegalStateException("GEMINI_NOT_CONFIGURED");
        long deadline=System.nanoTime()+Duration.ofMillis(policy.getTotalTimeoutMs()).toNanos();
        int count=0;Reply last=null;
        Map<String,Integer> used=new HashMap<>();
        while(count<policy.getMaxAttempts()) {
            long remaining=(deadline-System.nanoTime())/1_000_000;
            // Do not start a new network request in the last few milliseconds of the deadline.
            if(remaining<25) break;
            // URL-context + structured response is routed only to capable Gemini 3 models.
            String selected=models.stream().filter(m->(!request.has("tools") || m.startsWith("gemini-3")))
                    .filter(m->used.getOrDefault(m,0)<policy.getMaxAttemptsPerModel()
                    && cooldowns.getOrDefault(m,0L)<=System.currentTimeMillis()).findFirst().orElse(null);
            if(selected==null) break;
            count++;used.merge(selected,1,Integer::sum);
            long start=System.nanoTime();Reply reply;String error=null;long retryAfter=0;
            try {
                var http=HttpRequest.newBuilder(base.resolve("models/"+selected+":generateContent"))
                        .timeout(Duration.ofMillis(Math.max(1,Math.min(remaining,policy.getAttemptTimeoutMs()))))
                        .header("Content-Type","application/json").header("x-goog-api-key",key)
                        .POST(HttpRequest.BodyPublishers.ofString(request.toString())).build();
                var response=client.send(http,HttpResponse.BodyHandlers.ofString(java.nio.charset.StandardCharsets.UTF_8));
                reply=parse(response.statusCode(),response.body(),selected);
                retryAfter=retryAfterMillis(response.headers().firstValue("Retry-After").orElse(""));
                if(response.statusCode()<200||response.statusCode()>=300) error="HTTP_"+response.statusCode();
            } catch(InterruptedException e) {
                audit.accept(new Attempt(count,selected,null,"INTERRUPTED",null,null,null,null,elapsed(start)));
                Thread.currentThread().interrupt();throw e;
            } catch(IOException e) {
                reply=null;error=e instanceof HttpTimeoutException?"TIMEOUT":"TRANSPORT_IO";
            }
            // Persist each attempt before switching models. Keys/headers are never recorded.
            audit.accept(new Attempt(count,selected,reply==null?null:reply.httpStatus(),error,
                    reply==null?null:reply.rawBody(),reply==null?null:reply.outputText(),
                    reply==null?null:reply.inputTokens(),reply==null?null:reply.outputTokens(),elapsed(start)));
            if(reply!=null) {
                last=reply;
                // Invalid credentials/request or a model's content/safety decision are terminal.
                if(!Set.of(404,408,429,500,502,503,504).contains(reply.httpStatus())) return reply;
            }
            if(reply!=null&&(reply.httpStatus()==429||reply.httpStatus()==404))
                cooldowns.put(selected,System.currentTimeMillis()+Math.max(policy.getCooldownMs(),retryAfter));
            long wait=Math.min(policy.getMaxBackoffMs(),(long)policy.getBackoffMs()*(1L<<Math.min(count-1,5)));
            if(wait>0) wait=java.util.concurrent.ThreadLocalRandom.current().nextLong(Math.max(1,wait/2),wait+1);
            if(count>=policy.getMaxAttempts() || (deadline-System.nanoTime())/1_000_000<=wait) break;
            if(wait>0) Thread.sleep(wait);
        }
        if(last!=null) return last;
        throw new IOException("GEMINI_ROUTING_EXHAUSTED_OR_DEADLINE");
    }
    private static int elapsed(long start) {return (int)((System.nanoTime()-start)/1_000_000);}
    static long retryAfterMillis(String value) {
        try {return Math.min(86400,Math.max(0,Long.parseLong(value.trim())))*1000;}
        catch(Exception e) {
            try {return Math.max(0,Math.min(86400000,java.time.ZonedDateTime.parse(value,java.time.format.DateTimeFormatter.RFC_1123_DATE_TIME)
                    .toInstant().toEpochMilli()-System.currentTimeMillis()));} catch(Exception ignored) {return 0;}
        }
    }
    private Reply parse(int status,String raw,String selected) {
        JsonNode body;
        try { body=json.read(raw); } catch(IllegalArgumentException e) { return new Reply(status,raw,null,null,null,selected); }
        var candidate=body.path("candidates").path(0);
        String output=null;
        if("STOP".equals(candidate.path("finishReason").asText())) {
            var text=new StringBuilder();
            candidate.path("content").path("parts").forEach(p->{if(!p.path("thought").asBoolean() && p.has("text")) text.append(p.path("text").asText());});
            if(!text.isEmpty()) output=text.toString();
        }
        var usage=body.path("usageMetadata");
        return new Reply(status,raw,output,
                usage.has("promptTokenCount")?usage.path("promptTokenCount").asInt():null,
                usage.has("candidatesTokenCount")?usage.path("candidatesTokenCount").asInt():null,selected);
    }
}
