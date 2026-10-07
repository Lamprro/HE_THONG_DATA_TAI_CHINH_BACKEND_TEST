package com.hethongdata.taichinh.service.market;

import com.hethongdata.taichinh.entity.ingestion.DataSourceEntity;
import com.hethongdata.taichinh.entity.ingestion.IngestionJobEntity;
import com.hethongdata.taichinh.entity.ingestion.IngestionRunEntity;
import com.hethongdata.taichinh.entity.ingestion.RawPayloadEntity;
import com.hethongdata.taichinh.entity.validation.DataVersionEntity;
import com.hethongdata.taichinh.repository.ingestion.IngestionRunRepository;
import com.hethongdata.taichinh.repository.jpa.ingestion.RawPayloadJpaRepository;
import com.hethongdata.taichinh.repository.jpa.validation.DataVersionJpaRepository;
import com.hethongdata.taichinh.service.validation.DataVersionLifecycleService;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

class MarketPriceWorkflowServiceTests {
    @Test
    void rejectsFailedVersionAndContinuesWithNextVersion() {
        DataVersionJpaRepository versions = mock(DataVersionJpaRepository.class);
        RawPayloadJpaRepository raws = mock(RawPayloadJpaRepository.class);
        IngestionRunRepository runs = mock(IngestionRunRepository.class);
        MarketPriceWorkflowPersistenceService writes = mock(MarketPriceWorkflowPersistenceService.class);
        DataVersionLifecycleService lifecycle = mock(DataVersionLifecycleService.class);
        IngestionJobEntity job = mock(IngestionJobEntity.class);
        DataSourceEntity source = mock(DataSourceEntity.class);
        IngestionRunEntity firstRun = run(), secondRun = run();
        DataVersionEntity first = version(), second = version();
        RawPayloadEntity quote = mock(RawPayloadEntity.class);
        when(quote.getEntityType()).thenReturn("QUOTE");
        when(job.getCode()).thenReturn(MarketPriceWorkflowService.WORKFLOW);
        when(job.getDataSource()).thenReturn(source);
        when(versions.findByDataDomainAndStatusOrderByCreatedAtAsc("MARKET_PRICE", "ACTIVE"))
                .thenReturn(List.of(first, second));
        when(raws.findByIngestionRunIdOrderByFetchedAtDesc(any(UUID.class)))
                .thenReturn(List.of(quote));
        when(runs.startInternalBatch(source, job, "SCHEDULED", MarketPriceWorkflowService.WORKFLOW))
                .thenReturn(firstRun, secondRun);
        RuntimeException failure = new IllegalArgumentException("invalid payload");
        doThrow(failure).when(writes).build(first.getId(), firstRun);

        var result = new MarketPriceWorkflowService(
                versions, raws, runs, writes, lifecycle).execute(job, "SCHEDULED");

        verify(lifecycle).rejectBuildFailure(first.getId(), MarketPriceWorkflowService.WORKFLOW, failure);
        verify(writes).build(second.getId(), secondRun);
        assertThat(result.getStatus()).isEqualTo("COMPLETED_WITH_REJECTIONS");
    }

    private DataVersionEntity version() {
        DataVersionEntity version = DataVersionEntity.acceptedForRun(
                "MARKET_PRICE", UUID.randomUUID(), 1, "a".repeat(64));
        ReflectionTestUtils.setField(version, "id", UUID.randomUUID());
        return version;
    }

    private IngestionRunEntity run() {
        IngestionRunEntity run = mock(IngestionRunEntity.class);
        when(run.getId()).thenReturn(UUID.randomUUID());
        return run;
    }
}
