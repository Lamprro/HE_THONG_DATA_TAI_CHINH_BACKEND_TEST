package com.hethongdata.taichinh.integration.cloudinary;

import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.validation.annotation.Validated;

/** Storage settings: binding this bean does not start the separately configured recovery worker. */
@Validated
@ConfigurationProperties(prefix = "financial.documents.cloudinary")
public record CloudinaryDocumentProperties(
    @DefaultValue("false") boolean enabled,
    @DefaultValue("") String cloudName,
    @DefaultValue("") String apiKey,
    @DefaultValue("") String apiSecret,
    @DefaultValue("financial/news-documents") String folder,
    @DefaultValue("20971520") @Min(1) @Max(52428800) long maxFileBytes,
    @DefaultValue("5s") Duration connectTimeout,
    @DefaultValue("45s") Duration readTimeout) {

  public boolean credentialsPresent() {
    return cloudName != null
        && !cloudName.isBlank()
        && apiKey != null
        && !apiKey.isBlank()
        && apiSecret != null
        && !apiSecret.isBlank();
  }

  @AssertTrue(message = "Cloudinary credentials are required when document storage is enabled")
  public boolean isCredentialConfigurationValid() {
    return !enabled || credentialsPresent();
  }

  @AssertTrue(
      message =
          "Cloudinary folder/cloud name must be safe identifiers and timeouts must be positive and"
              + " bounded")
  public boolean isStorageConfigurationValid() {
    return cloudName != null
        && (cloudName.isEmpty() || cloudName.matches("[a-zA-Z0-9_-]+"))
        && folder != null
        && folder.matches("[a-zA-Z0-9_-]+(/[a-zA-Z0-9_-]+)*")
        && positiveBounded(connectTimeout, 30)
        && positiveBounded(readTimeout, 180);
  }

  private static boolean positiveBounded(Duration duration, long seconds) {
    return duration != null
        && !duration.isNegative()
        && !duration.isZero()
        && duration.compareTo(Duration.ofSeconds(seconds)) <= 0;
  }

  // Never use the record-generated representation: it would include API credentials.
  @Override
  public String toString() {
    return "CloudinaryDocumentProperties[enabled="
        + enabled
        + ", credentialsPresent="
        + credentialsPresent()
        + "]";
  }
}
