package com.hethongdata.taichinh.service.ingestion;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import com.hethongdata.taichinh.dto.ingestion.IngestionExecutionResponse;
import com.hethongdata.taichinh.entity.ingestion.IngestionJobEntity;
import com.hethongdata.taichinh.repository.ingestion.IngestionJobRepository;
import com.hethongdata.taichinh.repository.jpa.ingestion.IngestionRunJpaRepository;
import com.hethongdata.taichinh.service.financial.*;
import com.hethongdata.taichinh.service.macro.MacroWorkflowService;
import com.hethongdata.taichinh.service.market.*;
import com.hethongdata.taichinh.service.news.NewsWorkflowService;
import java.time.Instant;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.test.util.ReflectionTestUtils;

class IntegratedJobDispatchTests {
    final IngestionJobRepository jobs=mock(IngestionJobRepository.class);
    final IngestionRunJpaRepository runs=mock(IngestionRunJpaRepository.class);
    final IngestionService provider=mock(IngestionService.class);
    final RetryBudgetService budget=mock(RetryBudgetService.class);
    final NewsWorkflowService news=mock(NewsWorkflowService.class);
    final FinancialStatementBuildService statements=mock(FinancialStatementBuildService.class);
    final MacroWorkflowService macro=mock(MacroWorkflowService.class);
    final FinancialMetricWorkflowService metrics=mock(FinancialMetricWorkflowService.class);
    final FinancialMetricCalculateService calculate=mock(FinancialMetricCalculateService.class);
    final MarketPriceWorkflowService prices=mock(MarketPriceWorkflowService.class);
    final MarketIndexWorkflowService indexes=mock(MarketIndexWorkflowService.class);
    final IngestionJobService service=new IngestionJobService(jobs,runs,provider,budget,news,
            statements,macro,metrics,calculate,prices,indexes);
    final IngestionExecutionResponse response=new IngestionExecutionResponse(UUID.randomUUID(),null,
            "SUCCESS",200,"application/json",null,false);

    IngestionJobEntity job(String code) {
        var job=mock(IngestionJobEntity.class);
        when(job.getId()).thenReturn(UUID.randomUUID());when(job.getCode()).thenReturn(code);
        when(job.isActive()).thenReturn(true);
        when(jobs.findEntityById(job.getId())).thenReturn(Optional.of(job));return job;
    }

    @ParameterizedTest
    @ValueSource(strings={"MACRO","NEWS","STATEMENTS","METRICS","CALCULATE","PRICES","INDEXES"})
    void mergedWorkflowsNeverFallThroughToRawProvider(String code) {
        var job=job(code);
        switch(code) {
            case "MACRO" -> {when(macro.supports(code)).thenReturn(true);when(macro.execute(job,"MANUAL")).thenReturn(response);}
            case "NEWS" -> {when(news.supports(code)).thenReturn(true);when(news.execute(job,"MANUAL")).thenReturn(response);}
            case "STATEMENTS" -> {when(statements.supports(code)).thenReturn(true);when(statements.execute(job,"MANUAL")).thenReturn(response);}
            case "METRICS" -> {when(metrics.supports(code)).thenReturn(true);when(metrics.execute(job,"MANUAL")).thenReturn(response);}
            case "CALCULATE" -> {when(calculate.supports(code)).thenReturn(true);when(calculate.execute(job,"MANUAL")).thenReturn(response);}
            case "PRICES" -> {when(prices.supports(code)).thenReturn(true);when(prices.execute(job,"MANUAL")).thenReturn(response);}
            case "INDEXES" -> {when(indexes.supports(code)).thenReturn(true);when(indexes.execute(job,"MANUAL")).thenReturn(response);}
        }
        assertThat(service.runNow(job.getId())).isSameAs(response);
        verifyNoInteractions(provider);verify(budget).resetAfterSuccess(job);
    }

    @Test void macroSchedulerKeepsItsOwnScopeWhenGeneralAllowlistIsNewsOnly() {
        String code=MacroWorkflowService.JOBS.iterator().next();var macroJob=job(code);var newsJob=job("NEWS");
        when(macroJob.getCronExpression()).thenReturn("* * * * * *");
        when(macroJob.getCreatedAt()).thenReturn(Instant.now().minusSeconds(60));
        when(runs.findTopByIngestionJobIdOrderByStartedAtDesc(macroJob.getId())).thenReturn(Optional.empty());
        when(jobs.findActiveEntities()).thenReturn(List.of(macroJob,newsJob));
        when(macro.supports(code)).thenReturn(true);when(macro.execute(macroJob,"SCHEDULED")).thenReturn(response);
        ReflectionTestUtils.setField(service,"scheduledJobCodes","NEWS");
        service.executeDueMacroJobs();
        verify(macro).execute(macroJob,"SCHEDULED");verifyNoInteractions(provider,news);
    }
}
