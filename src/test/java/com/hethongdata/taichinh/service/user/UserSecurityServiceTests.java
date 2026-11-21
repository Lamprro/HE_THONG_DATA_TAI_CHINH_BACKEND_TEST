package com.hethongdata.taichinh.service.user;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import com.hethongdata.taichinh.repository.jdbc.user.UserSecurityQueryRepository;
import java.util.*;
import org.junit.jupiter.api.Test;

class UserSecurityServiceTests {
  @Test void absentRegistryIsReportedWithoutReadingResearchTables() {
    var securityRepo = mock(UserSecurityQueryRepository.class);
    var read = mock(UserReadSupport.class);
    when(securityRepo.hasModelVersionsColumns()).thenReturn(false);
    var service = new UserSecurityService(securityRepo, read, null, null, null, mock(UserLocalAiService.class));
    var local = (Map<?, ?>) service.availability().data().get("localAi");
    assertThat(local.get("registryAvailable")).isEqualTo(false);
    assertThat(local.get("publicPredictionReader")).isEqualTo("NOT_INTEGRATED");
    verify(securityRepo, never()).queryModelVersionRegistry();
  }

  @Test void activeRegistryIsNotInterpretedAsPublicPredictionApproval() {
    var securityRepo = mock(UserSecurityQueryRepository.class);
    var read = mock(UserReadSupport.class);
    when(securityRepo.hasModelVersionsColumns()).thenReturn(true);
    when(securityRepo.queryModelVersionRegistry()).thenReturn(
        List.of(Map.of("target", "STOCK_DIRECTION_NEXT_QUARTER", "status", "ACTIVE", "count", "1")));
    var service = new UserSecurityService(securityRepo, read, null, null, null, mock(UserLocalAiService.class));
    String body = service.availability().data().toString();
    assertThat(body).contains("ACTIVE", "PUBLICATION_POLICY_REQUIRED", "NOT_INTEGRATED")
        .doesNotContain("artifact_uri", "hyperparameters", "source_snapshot");
  }
}
