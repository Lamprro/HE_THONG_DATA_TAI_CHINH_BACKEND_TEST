package com.hethongdata.taichinh.service.llm;

import com.fasterxml.jackson.databind.JsonNode;
import com.hethongdata.taichinh.repository.jdbc.llm.PromptTemplateJdbcRepository;
import com.hethongdata.taichinh.service.ingestion.ChecksumService;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.util.List;
import java.util.UUID;

@Service
public class LlmPromptCatalog {
    public static final List<String> TASKS = List.of("NEWS_SUMMARY", "NEWS_DETAIL", "NEWS_FINANCIAL_FACTS");
    public record Template(UUID id, String task, String version, String prompt,
                           JsonNode requestSchema, JsonNode responseSchema, String checksum) {}
    private final PromptTemplateJdbcRepository repository;
    private final LlmJson json;
    private final ChecksumService hashes;

    public LlmPromptCatalog(PromptTemplateJdbcRepository repository, LlmJson json, ChecksumService hashes) {
        this.repository=repository; this.json=json; this.hashes=hashes;
    }

    @Transactional
    public int seed() {
        int count=0;
        for (String task : TASKS) {
            JsonNode resource;
            try (var stream=new ClassPathResource("llm/"+task.toLowerCase(java.util.Locale.ROOT)+".json").getInputStream()) {
                resource=json.mapper().readTree(stream);
            } catch(Exception e) { throw new IllegalStateException("Cannot load prompt "+task,e); }
            String checksum=hashes.sha256(resource.toString());
            repository.lockPrompt(task);
            var existing = repository.findChecksum(task, resource.path("version").asText());
            if (existing.isPresent()) {
                if (!existing.get().equals(checksum)) throw new IllegalStateException("Prompt is immutable; increment version: "+task);
                continue;
            }
            // Explicit seeding promotes a new immutable version atomically; previous runs keep their FK.
            repository.disableTaskPrompts(task);
            count += repository.insertActiveVersion(UUID.randomUUID(), task, resource.path("version").asText(),
                    resource.path("input_domain").asText(), resource.path("description").asText(),
                    resource.path("system_prompt").asText(), resource.path("request_schema").toString(),
                    resource.path("response_schema").toString(), checksum);
        }
        return count;
    }

    public Template active(String task) {
        if (!TASKS.contains(task)) throw new IllegalArgumentException("Unsupported NEWS task: "+task);
        var record = repository.findActiveByTaskAndDomain(task, "NEWS")
                .orElseThrow(() -> new IllegalArgumentException("No active prompt: "+task));
        return new Template(record.id(), task, record.version(), record.systemPrompt(),
                json.read(record.requestSchema()), json.read(record.responseSchema()), record.checksum());
    }

    public List<Template> activeTemplates() {
        return repository.findActiveByDomain("NEWS").stream()
                .map(r -> new Template(r.id(), r.taskCode(), r.version(), r.systemPrompt(),
                        json.read(r.requestSchema()), json.read(r.responseSchema()), r.checksum()))
                .toList();
    }
}
