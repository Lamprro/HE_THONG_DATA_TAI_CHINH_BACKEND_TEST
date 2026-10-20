package com.hethongdata.taichinh.service.admin;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import com.fasterxml.jackson.databind.*;
import com.hethongdata.taichinh.dto.ingestion.*;
import com.hethongdata.taichinh.entity.ingestion.*;
import com.hethongdata.taichinh.entity.validation.DataVersionEntity;
import com.hethongdata.taichinh.repository.ingestion.IngestionJobRepository;
import com.hethongdata.taichinh.repository.jpa.ingestion.*;
import com.hethongdata.taichinh.repository.jpa.validation.DataVersionJpaRepository;
import com.hethongdata.taichinh.service.ingestion.*;
import com.hethongdata.taichinh.service.validation.ValidationJobService;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.test.util.ReflectionTestUtils;

class AdminPipelineServiceTests {
  final ObjectMapper json=new ObjectMapper().findAndRegisterModules();
  final IngestionService ingest=mock(IngestionService.class);final ValidationJobService validation=mock(ValidationJobService.class);
  final IngestionJobService jobs=mock(IngestionJobService.class);final IngestionJobRepository definitions=mock(IngestionJobRepository.class);
  final RawPayloadJpaRepository raws=mock(RawPayloadJpaRepository.class);final IngestionRunJpaRepository runs=mock(IngestionRunJpaRepository.class);
  final DataVersionJpaRepository versions=mock(DataVersionJpaRepository.class);
  final AdminPipelineService service=new AdminPipelineService(ingest,validation,jobs,definitions,raws,runs,versions,json);
  UUID fixture(String operation) {
    UUID id=UUID.randomUUID();var run=mock(IngestionRunEntity.class);var raw=mock(RawPayloadEntity.class);
    when(run.getRequestQueryParams()).thenReturn(json.createObjectNode().put("operation",operation));when(runs.findById(id)).thenReturn(Optional.of(run));
    when(raw.getId()).thenReturn(UUID.randomUUID());when(raws.findByIngestionRunIdOrderByFetchedAtDesc(id)).thenReturn(List.of(raw));return id;
  }
  @Test void validationWithoutAcceptedVersionNeverCallsBuilder() {
    UUID run=fixture("OHLCV");when(versions.findByIngestionRunId(run)).thenReturn(Optional.empty());
    var progress=new ArrayList<JsonNode>();var result=service.processRun(run,progress::add);
    assertThat(result.path("status").asText()).isEqualTo("VALIDATION_BLOCKED");verifyNoInteractions(jobs,definitions);
    verify(validation).validate(any());assertThat(progress.getLast()).isEqualTo(result);
  }
  @ParameterizedTest @CsvSource({"ACTIVATED,ALREADY_ACTIVATED,COMPLETE","REJECTED,REJECTED,STOPPED_AT_VALIDATION"})
  void terminalSourceResumePersistsItsActualFinalStage(String state,String status,String stage) {
    UUID run=fixture("OHLCV");var version=DataVersionEntity.acceptedForRun("MARKET_PRICE",run,1,"fixture");
    ReflectionTestUtils.setField(version,"id",UUID.randomUUID());ReflectionTestUtils.setField(version,"status",state);
    when(versions.findByIngestionRunId(run)).thenReturn(Optional.of(version));
    var progress=new ArrayList<JsonNode>();var result=service.processRun(run,progress::add);
    assertThat(result.path("status").asText()).isEqualTo(status);assertThat(result.path("stage").asText()).isEqualTo(stage);
    assertThat(progress.getLast()).isEqualTo(result);verifyNoInteractions(jobs,definitions,ingest);
  }
  @ParameterizedTest @CsvSource({"OHLCV,MARKET_PRICE,MARKET_PRICE_BUILD","FINANCIAL_STATEMENT,FINANCIAL_STATEMENT,FINANCIAL_STATEMENT_BUILD","RATIO,FINANCIAL_METRIC,FINANCIAL_METRIC_BUILD","INDEX_OHLCV,MARKET_INDEX,INDEX_PRICE_BUILD","INDEX_MEMBERS,MARKET_INDEX,INDEX_MEMBERSHIP_BUILD","FOREIGN_TRADES,MARKET_FOREIGN,FOREIGN_TRADE_BUILD","COMPANY,SECURITY_REFERENCE,SECURITY_REFERENCE_BUILD"})
  void usesOnlyMatchingNativeSelectedVersionBuilder(String operation,String domain,String code) {
    UUID run=fixture(operation),versionId=UUID.randomUUID(),jobId=UUID.randomUUID();
    var version=DataVersionEntity.acceptedForRun(domain,run,1,"fixture");ReflectionTestUtils.setField(version,"id",versionId);
    when(versions.findByIngestionRunId(run)).thenReturn(Optional.of(version));when(versions.findById(versionId)).thenReturn(Optional.of(version));
    var job=mock(IngestionJobEntity.class);when(job.getCode()).thenReturn(code);when(job.getId()).thenReturn(jobId);when(definitions.findAllEntities()).thenReturn(List.of(job));
    when(jobs.runVersion(jobId,versionId)).thenAnswer(call->{ReflectionTestUtils.setField(version,"status","ACTIVATED");return new IngestionExecutionResponse(UUID.randomUUID(),null,"SUCCESS",200,"application/json",null,false);});
    var result=service.processRun(run,p->{});assertThat(result.path("status").asText()).isEqualTo("ACTIVATED");
    verify(jobs).runVersion(jobId,versionId);verify(jobs,never()).runOnce(any());verifyNoInteractions(ingest);
  }
  @Test void unsupportedRawOnlyOperationIsRejectedBeforeFetching() {
    var request=new ManualIngestionRequest();request.setOperation(com.hethongdata.taichinh.application.port.model.ExternalOperation.MANAGEMENT);
    assertThatThrownBy(()->service.execute(request,p->{})).isInstanceOf(IllegalArgumentException.class);verifyNoInteractions(ingest);
  }
}
