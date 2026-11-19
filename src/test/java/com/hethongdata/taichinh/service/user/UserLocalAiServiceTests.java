package com.hethongdata.taichinh.service.user;

import static org.assertj.core.api.Assertions.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.springframework.web.server.ResponseStatusException;

class UserLocalAiServiceTests {
  private final ObjectMapper json=new ObjectMapper();

  @Test void disabledPreviewIsNotFoundAndTouchesNothing(){
    var service=new UserLocalAiService(false,null,json);
    assertThat(service.previewEnabled()).isFalse();
    assertThatThrownBy(()->service.preview("FPT","HOSE",null)).isInstanceOf(ResponseStatusException.class).hasMessageContaining("disabled");
  }

  @Test void projectsOnlyContractFieldsAsDecimalStrings(){
    var service=new UserLocalAiService(true,null,json);
    var row=new LinkedHashMap<String,Object>();
    row.put("modelId","direction_h1");row.put("kind","h3");
    row.put("prediction","{\"direction\":\"UP\",\"score_up\":0.6123456789012345678,\"threshold\":0.5,\"features\":{\"x\":1},\"artifact\":\"C:/models/a.pkl\"}");
    var result=service.projectPreview(row);
    assertThat(result).doesNotContainKey("prediction").containsEntry("readiness","RESEARCH_ONLY");
    @SuppressWarnings("unchecked") var values=(Map<String,Object>)result.get("values");
    assertThat(values).containsOnlyKeys("direction","score_up","threshold");
    assertThat(values.get("direction")).isEqualTo("UP");
    assertThat(values.get("score_up")).isEqualTo("0.6123456789012345678");
    assertThat(result.toString()).doesNotContain("pkl").doesNotContain("features");
  }

  @Test void unknownKindOrBrokenJsonIsDropped(){
    var service=new UserLocalAiService(true,null,json);
    assertThat(service.projectPreview(new HashMap<>(Map.of("kind","secret","prediction","{}")))).isNull();
    assertThat(service.projectPreview(new HashMap<>(Map.of("kind","npat","prediction","not-json")))).isNull();
    var npat=service.projectPreview(new HashMap<>(Map.of("kind","npat","prediction","{\"prediction_vnd\":12345678901234567890}")));
    assertThat(npat.get("values")).isEqualTo(Map.of("prediction_vnd","12345678901234567890"));
  }
}
