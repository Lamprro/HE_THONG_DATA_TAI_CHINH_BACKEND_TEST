package com.hethongdata.taichinh.service.admin;

import com.hethongdata.taichinh.dto.admin.AdminConsoleDtos.*;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.hethongdata.taichinh.repository.jpa.admin.AdminCommandJpaRepository;
import com.hethongdata.taichinh.service.ingestion.*;
import com.hethongdata.taichinh.service.validation.ValidationJobService;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import java.util.*;

class AdminWorkerSwitchTests {
  @Test void disabledCommandWorkerCannotClaimExpireOrQueueWork() {
    var repo=mock(AdminCommandJpaRepository.class);var jobs=mock(IngestionJobService.class);
    var service=new AdminCommandService(repo,new ObjectMapper(),jobs,mock(IngestionService.class),mock(ValidationJobService.class),
        mock(jakarta.validation.Validator.class),mock(org.springframework.transaction.PlatformTransactionManager.class),mock(AdminPipelineService.class),mock(AdminAiActionService.class));
    ReflectionTestUtils.setField(service,"enabled",false);
    try {
      service.start();ReflectionTestUtils.invokeMethod(service,"work");ReflectionTestUtils.invokeMethod(service,"leases");
      assertThat(service.workerEnabled()).isFalse();
      assertThatThrownBy(()->service.submit(new CommandRequest(UUID.randomUUID(),"RUN_JOB","test",new ObjectMapper().createObjectNode())))
          .hasMessageContaining("503");
      verifyNoInteractions(repo,jobs);
    } finally {service.stop();}
  }
}
