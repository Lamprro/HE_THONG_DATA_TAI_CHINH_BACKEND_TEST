package com.hethongdata.taichinh.service.admin;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.hethongdata.taichinh.service.ingestion.*;
import com.hethongdata.taichinh.service.validation.ValidationJobService;
import com.hethongdata.taichinh.repository.ingestion.IngestionJobRepository;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.test.util.ReflectionTestUtils;
import java.util.*;

class AdminWorkerSwitchTests {
  @Test void disabledCommandWorkerCannotClaimExpireOrQueueWork() {
    var db=mock(JdbcTemplate.class);var jobs=mock(IngestionJobService.class);
    var service=new AdminCommandService(db,new ObjectMapper(),jobs,mock(IngestionService.class),mock(ValidationJobService.class),
        mock(jakarta.validation.Validator.class),mock(org.springframework.transaction.PlatformTransactionManager.class));
    ReflectionTestUtils.setField(service,"enabled",false);
    try {
      service.start();ReflectionTestUtils.invokeMethod(service,"work");ReflectionTestUtils.invokeMethod(service,"leases");
      assertThat(service.workerEnabled()).isFalse();
      assertThatThrownBy(()->service.submit(new AdminCommandService.Request(UUID.randomUUID(),"RUN_JOB","test",new ObjectMapper().createObjectNode())))
          .hasMessageContaining("503");
      verifyNoInteractions(db,jobs);
    } finally {service.stop();}
  }
  @Test void disabledScheduleWorkerCannotHeartbeatOrEnablePolicy() {
    var db=mock(JdbcTemplate.class);var jobs=mock(IngestionJobService.class);
    var service=new AdminScheduleService(db,new ObjectMapper(),jobs,mock(IngestionJobRepository.class),
        new MockEnvironment().withProperty("financial.admin.schedule.enabled","false"),mock(NativeJobGuard.class));
    try {
      service.start();ReflectionTestUtils.invokeMethod(service,"tick");
      assertThatThrownBy(()->service.update(new AdminScheduleService.Request(true,Set.of("JOB"),0,"test"))).hasMessageContaining("503");
      verifyNoInteractions(db,jobs);
    } finally {service.stop();}
  }
}
