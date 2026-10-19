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
    @Test void selectedVersionUsesNativeWriterWithoutScanningOtherPendingVersions() {
        var versions=mock(DataVersionJpaRepository.class);var raws=mock(RawPayloadJpaRepository.class);
        var runs=mock(IngestionRunRepository.class);var writes=mock(MarketPriceWorkflowPersistenceService.class);
        var lifecycle=mock(DataVersionLifecycleService.class);var job=mock(IngestionJobEntity.class);
        var version=version();var raw=mock(RawPayloadEntity.class);var run=run();
        when(job.getCode()).thenReturn(MarketPriceWorkflowService.WORKFLOW);
        when(versions.findById(version.getId())).thenReturn(java.util.Optional.of(version));
        when(raw.getEntityType()).thenReturn("OHLCV");
        when(raws.findByIngestionRunIdOrderByFetchedAtDesc(version.getIngestionRunId())).thenReturn(List.of(raw));
        when(runs.startInternalBatch(null,job,"MANUAL",MarketPriceWorkflowService.WORKFLOW)).thenReturn(run);
        var service=new MarketPriceWorkflowService(versions,raws,runs,writes,lifecycle);
        assertThat(service.executeVersion(job,version.getId(),"MANUAL").getRunId()).isEqualTo(run.getId());
        verify(writes).build(version.getId(),run);
        verify(versions,never()).findByDataDomainAndStatusOrderByCreatedAtAsc(anyString(),anyString());
        ReflectionTestUtils.setField(version,"status","REJECTED");
        org.assertj.core.api.Assertions.assertThatThrownBy(()->service.executeVersion(job,version.getId(),"MANUAL"))
                .isInstanceOf(IllegalArgumentException.class);
        verify(writes,times(1)).build(any(),any());
    }
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
