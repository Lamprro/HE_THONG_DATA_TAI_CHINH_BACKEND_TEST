package com.hethongdata.taichinh.scheduler.llm;

import com.hethongdata.taichinh.dto.news.NewsRecoveryDtos.ExecuteRequest;
import com.hethongdata.taichinh.service.news.recovery.*;
import java.util.List;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** Opt-in worker; recovers URL-only sources, never auto-approves a proposal. */
@Component
@ConditionalOnProperty(name = "financial.news-recovery.scheduler.enabled", havingValue = "true")
public class NewsRecoveryScheduler {
  private final NewsRecoveryStore store;
  private final NewsRecoveryService service;

  public NewsRecoveryScheduler(NewsRecoveryStore store, NewsRecoveryService service) {
    this.store = store;
    this.service = service;
  }

  @Scheduled(fixedDelayString = "${financial.news-recovery.scheduler.delay-ms:300000}")
  public void tick() {
    if (!service.configuration().llmConfigured()) return;
    for (var candidate : store.candidates(1))
      try {
        service.execute(candidate.articleId(), new ExecuteRequest(List.of()));
      } catch (Exception error) {
        LoggerFactory.getLogger(getClass())
            .warn(
                "News recovery failed for {} ({})",
                candidate.articleId(),
                error.getClass().getSimpleName());
      }
  }
}
