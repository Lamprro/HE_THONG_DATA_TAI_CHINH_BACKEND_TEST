package com.hethongdata.taichinh.service.user;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import java.util.*;
import org.junit.jupiter.api.Test;
class UserOverviewServiceTests {
  @Test void absentRegistryIsReportedWithoutReadingResearchTables(){
    var read=mock(UserReadSupport.class);var service=new UserOverviewService(read,null,null,null,null,null,null,mock(UserLocalAiService.class));
    var local=(Map<?,?>)service.availability().data().get("localAi");assertThat(local.get("registryAvailable")).isEqualTo(false);
    assertThat(local.get("publicPredictionReader")).isEqualTo("NOT_INTEGRATED");verify(read,never()).rows(anyString(),anyMap());
  }
  @Test void activeRegistryIsNotInterpretedAsPublicPredictionApproval(){
    var read=mock(UserReadSupport.class);when(read.hasColumns(anyString(),anySet())).thenReturn(true);
    when(read.rows(anyString(),anyMap())).thenReturn(List.of(Map.of("target","STOCK_DIRECTION_NEXT_QUARTER","status","ACTIVE","count","1")));
    var service=new UserOverviewService(read,null,null,null,null,null,null,mock(UserLocalAiService.class));String body=service.availability().data().toString();
    assertThat(body).contains("ACTIVE","PUBLICATION_POLICY_REQUIRED","NOT_INTEGRATED").doesNotContain("artifact_uri","hyperparameters","source_snapshot");
  }
}
