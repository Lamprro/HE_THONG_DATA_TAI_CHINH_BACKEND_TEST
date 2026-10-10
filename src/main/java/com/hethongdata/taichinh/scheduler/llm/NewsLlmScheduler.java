package com.hethongdata.taichinh.scheduler.llm;

import com.hethongdata.taichinh.service.llm.LlmGateway;
import com.hethongdata.taichinh.service.llm.NewsLlmService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(name="financial.llm.scheduler.enabled",havingValue="true")
public class NewsLlmScheduler {
    private static final Logger LOG=LoggerFactory.getLogger(NewsLlmScheduler.class);
    private final NewsLlmService service;
    private final LlmGateway gateway;
    public NewsLlmScheduler(NewsLlmService service,LlmGateway gateway) {this.service=service;this.gateway=gateway;}
    @Scheduled(fixedDelayString="${financial.llm.scheduler.delay-ms:300000}")
    public void tick() {
        service.validatePending(5);
        if(!gateway.configured()) return;
        for(var article:service.candidates(5)) {
            try {service.executeAll(article);}
            catch(Exception e) {LOG.error("NEWS LLM processing failed for article {} ({})",article,e.getClass().getSimpleName());}
        }
    }
}
