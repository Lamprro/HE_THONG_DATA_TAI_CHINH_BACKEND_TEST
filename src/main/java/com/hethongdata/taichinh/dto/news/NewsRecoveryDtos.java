package com.hethongdata.taichinh.dto.news;

import com.fasterxml.jackson.databind.JsonNode;
import jakarta.validation.constraints.*;
import java.util.*;

public final class NewsRecoveryDtos {
  private NewsRecoveryDtos() {}

  public record ExecuteRequest(
      @Size(max = 3) List<@NotBlank @Size(max = 2048) String> documentUrls) {
    public ExecuteRequest {
      documentUrls = documentUrls == null ? List.of() : List.copyOf(documentUrls);
    }
  }

  public record ReviewRequest(
      @NotBlank @Size(max = 100) String reviewer, @NotBlank @Size(max = 2000) String reason) {}

  public record Outcome(String status, UUID runId, UUID articleId, List<String> issues) {}

  public record Candidate(UUID articleId, String title, String url, int contentLength) {}

  public record Configuration(
      boolean llmConfigured,
      boolean cloudinaryConfigured,
      boolean adminReviewRequired,
      int maxAttachments,
      int maxFileBytes) {}

  public record Run(
      UUID runId,
      UUID articleId,
      String status,
      JsonNode requestMetadata,
      JsonNode responseMetadata,
      JsonNode proposal,
      JsonNode errors) {}
}
