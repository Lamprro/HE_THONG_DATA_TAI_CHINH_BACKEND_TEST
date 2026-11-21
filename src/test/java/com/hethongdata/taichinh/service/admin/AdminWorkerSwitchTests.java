package com.hethongdata.taichinh.service.admin;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.hethongdata.taichinh.repository.jdbc.admin.AdminAuditJdbcRepository;
import com.hethongdata.taichinh.repository.jdbc.admin.AdminCommandJdbcRepository;
import com.hethongdata.taichinh.repository.jdbc.admin.AdminScheduleJdbcRepository;
import com.hethongdata.taichinh.service.ingestion.*;
import com.hethongdata.taichinh.service.validation.ValidationJobService;
import com.hethongdata.taichinh.repository.ingestion.IngestionJobRepository;
import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.test.util.ReflectionTestUtils;
import java.util.*;

class AdminWorkerSwitchTests {
  @Test void disabledCommandWorkerCannotClaimExpireOrQueueWork() {
    var repo=mock(AdminCommandJdbcRepository.class);var audit=mock(AdminAuditJdbcRepository.class);var jobs=mock(IngestionJobService.class);
    var service=new AdminCommandService(repo,audit,new ObjectMapper(),jobs,mock(IngestionService.class),mock(ValidationJobService.class),
        mock(jakarta.validation.Validator.class),mock(org.springframework.transaction.PlatformTransactionManager.class),mock(AdminPipelineService.class),mock(AdminAiActionService.class));
    ReflectionTestUtils.setField(service,"enabled",false);
    try {
      service.start();ReflectionTestUtils.invokeMethod(service,"work");ReflectionTestUtils.invokeMethod(service,"leases");
      assertThat(service.workerEnabled()).isFalse();
      assertThatThrownBy(()->service.submit(new AdminCommandService.Request(UUID.randomUUID(),"RUN_JOB","test",new ObjectMapper().createObjectNode())))
          .hasMessageContaining("503");
      verifyNoInteractions(repo,audit,jobs);
    } finally {service.stop();}
  }
  @Test void disabledScheduleWorkerCannotHeartbeatOrEnablePolicy() {
    var repo=mock(AdminScheduleJdbcRepository.class);var audit=mock(AdminAuditJdbcRepository.class);var jobs=mock(IngestionJobService.class);
    var service=new AdminScheduleService(repo,audit,new ObjectMapper(),jobs,mock(IngestionJobRepository.class),
        new MockEnvironment().withProperty("financial.admin.schedule.enabled","false"),mock(NativeJobGuard.class));
    try {
      service.start();ReflectionTestUtils.invokeMethod(service,"tick");
      assertThatThrownBy(()->service.update(new AdminScheduleService.Request(true,Set.of("JOB"),0,"test"))).hasMessageContaining("503");
      verifyNoInteractions(repo,audit,jobs);
    } finally {service.stop();}
  }
}
