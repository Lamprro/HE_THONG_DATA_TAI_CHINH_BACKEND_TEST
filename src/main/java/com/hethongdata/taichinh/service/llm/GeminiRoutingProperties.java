package com.hethongdata.taichinh.service.llm;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;
import org.springframework.validation.annotation.Validated;
import jakarta.validation.constraints.*;
import lombok.Getter;
import lombok.Setter;
import java.util.*;

@Component
@ConfigurationProperties("financial.llm.gemini")
@Validated
@Getter @Setter
public class GeminiRoutingProperties {
    private String models="gemini-3.5-flash-lite,gemini-3.5-flash,gemini-3.8-flash,gemini-3.1-flash-lite,gemini-3.6-flash,gemini-3.7-flash,gemini-2.5-flash,gemini-2.5-flash-lite";
    @Min(1) @Max(16) private int maxAttempts=8;
    @Min(1) @Max(3) private int maxAttemptsPerModel=1;
    @Min(100) @Max(90000) private int attemptTimeoutMs=45000;
    @Min(100) @Max(240000) private int totalTimeoutMs=180000;
    @Min(0) @Max(10000) private int backoffMs=1000;
    @Min(0) @Max(30000) private int maxBackoffMs=8000;
    @Min(100) @Max(3600000) private int cooldownMs=60000;
    @Min(1024) @Max(32768) private int maxOutputTokens=16384;
    @Min(0) @Max(8192) private int thinkingBudget=1024;
    public List<String> orderedModels(String primary) {
        var result=new LinkedHashSet<String>();
        if(primary!=null&&!primary.isBlank()) result.add(primary.trim());
        for(String item:models.split(",")) if(!item.isBlank()) result.add(item.trim());
        if(result.isEmpty()||result.size()>16||result.stream().anyMatch(m->!m.matches("gemini-[A-Za-z0-9._-]+")))
            throw new IllegalArgumentException("Use 1 to 16 Gemini API model IDs, separated by commas");
        return List.copyOf(result);
    }
}
