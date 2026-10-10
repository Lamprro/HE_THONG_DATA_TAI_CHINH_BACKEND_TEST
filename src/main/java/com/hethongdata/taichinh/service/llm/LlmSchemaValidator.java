package com.hethongdata.taichinh.service.llm;

import com.fasterxml.jackson.databind.JsonNode;
import com.networknt.schema.JsonSchemaFactory;
import com.networknt.schema.SpecVersion;
import java.util.List;

/** Shared structural validation; no NEWS/FINANCIAL business rules. */
public final class LlmSchemaValidator {
  private LlmSchemaValidator() {}

  public static List<String> validate(JsonNode schema, JsonNode value) {
    return JsonSchemaFactory.getInstance(SpecVersion.VersionFlag.V202012)
        .getSchema(schema)
        .validate(value)
        .stream()
        .map(Object::toString)
        .sorted()
        .toList();
  }
}
