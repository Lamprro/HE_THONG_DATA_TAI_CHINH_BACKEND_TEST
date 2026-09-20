package com.hethongdata.taichinh.service.news;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.hethongdata.taichinh.application.port.ExternalFinancialDataPort;
import com.hethongdata.taichinh.dto.ingestion.IngestionExecutionResponse;
import com.hethongdata.taichinh.entity.ingestion.DataSourceEntity;
import com.hethongdata.taichinh.entity.ingestion.IngestionJobEntity;
import com.hethongdata.taichinh.entity.ingestion.IngestionRunEntity;
import com.hethongdata.taichinh.repository.ingestion.IngestionRunRepository;
import com.hethongdata.taichinh.repository.jpa.ingestion.RawPayloadJpaRepository;
import com.hethongdata.taichinh.repository.jpa.validation.DataVersionJpaRepository;
import com.hethongdata.taichinh.service.ingestion.ChecksumService;
import com.hethongdata.taichinh.service.validation.DataVersionLifecycleService;

import org.junit.jupiter.api.Test;

import java.util.List;

class NewsWorkflowServiceTests {

    @Test
    void fetchJobRecordsSuccessfulNoWorkRunWhenNoActiveNewsVersionExists() {
        DataVersionJpaRepository versions = mock(DataVersionJpaRepository.class);
        RawPayloadJpaRepository rawPayloads = mock(RawPayloadJpaRepository.class);
        IngestionRunRepository runs = mock(IngestionRunRepository.class);
        ExternalFinancialDataPort external = mock(ExternalFinancialDataPort.class);
        NewsWorkflowPersistenceService writes = mock(NewsWorkflowPersistenceService.class);
        IngestionJobEntity job = mock(IngestionJobEntity.class);
        IngestionRunEntity run = mock(IngestionRunEntity.class);
        when(job.getCode()).thenReturn(NewsWorkflowService.NEWS_DATA_FETCH);
        when(job.getDataSource()).thenReturn(mock(DataSourceEntity.class));
        when(versions.findByDataDomainAndStatusOrderByCreatedAtAsc("NEWS", "ACTIVE"))
                .thenReturn(List.of());
        when(runs.startInternalBatch(job.getDataSource(), job, "MANUAL", NewsWorkflowService.NEWS_DATA_FETCH))
                .thenReturn(run);

        NewsWorkflowService service =
                new NewsWorkflowService(
                        versions,
                        rawPayloads,
                        runs,
                        external,
                        writes,
                        new ChecksumService(),
                        new ObjectMapper(),
                        mock(DataVersionLifecycleService.class));

        IngestionExecutionResponse result = service.execute(job, "MANUAL");

        assertThat(result.getStatus()).isEqualTo("SUCCESS");
        verify(writes).persistBuiltArticlesNoop(run, NewsWorkflowService.NEWS_DATA_FETCH);
        verifyNoInteractions(rawPayloads, external);
    }
}
