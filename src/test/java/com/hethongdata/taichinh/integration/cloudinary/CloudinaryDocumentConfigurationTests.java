package com.hethongdata.taichinh.integration.cloudinary;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

class CloudinaryDocumentConfigurationTests {
  private final ApplicationContextRunner runner =
      new ApplicationContextRunner().withUserConfiguration(CloudinaryDocumentConfiguration.class);

  @Test
  void disabledByDefaultWithoutCredentials() {
    runner.run(
        context -> {
          assertThat(context).hasNotFailed();
          var config = context.getBean(CloudinaryDocumentProperties.class);
          assertThat(config.enabled()).isFalse();
          assertThat(config.credentialsPresent()).isFalse();
          assertThat(config.maxFileBytes()).isEqualTo(20971520);
        });
  }

  @Test
  void enabledWithoutCredentialsFailsClosed() {
    runner
        .withPropertyValues("financial.documents.cloudinary.enabled=true")
        .run(context -> assertThat(context).hasFailed());
  }

  @Test
  void bindsCredentialsWithoutPrintingThem() {
    runner
        .withPropertyValues(
            "financial.documents.cloudinary.enabled=true",
            "financial.documents.cloudinary.cloud-name=test-cloud",
            "financial.documents.cloudinary.api-key=test-key-not-real",
            "financial.documents.cloudinary.api-secret=test-secret-not-real")
        .run(
            context -> {
              assertThat(context).hasNotFailed();
              var config = context.getBean(CloudinaryDocumentProperties.class);
              assertThat(config.credentialsPresent()).isTrue();
              assertThat(config.toString())
                  .doesNotContain("test-key-not-real", "test-secret-not-real");
            });
  }

  @Test
  void rejectsUnsafeOrUnboundedSettings() {
    for (String setting :
        new String[] {
          "folder=../documents",
          "max-file-bytes=0",
          "max-file-bytes=999999999",
          "read-timeout=0s",
          "connect-timeout=90s"
        }) {
      runner
          .withPropertyValues("financial.documents.cloudinary." + setting)
          .run(context -> assertThat(context).hasFailed());
    }
  }
}
