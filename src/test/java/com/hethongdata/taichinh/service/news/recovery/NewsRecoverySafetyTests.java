package com.hethongdata.taichinh.service.news.recovery;

import static org.assertj.core.api.Assertions.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.hethongdata.taichinh.integration.cloudinary.*;
import com.hethongdata.taichinh.service.llm.LlmSchemaValidator;
import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.zip.*;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;

class NewsRecoverySafetyTests {
  private final ObjectMapper json = new ObjectMapper();

  @Test
  void rejectsPrivateNetworksAndUnsafeUris() throws Exception {
    for (String ip :
        List.of(
            "127.0.0.1",
            "10.0.0.2",
            "172.16.0.2",
            "192.168.1.1",
            "169.254.169.254",
            "100.64.0.1",
            "::1",
            "fc00::1",
            "fe80::1"))
      assertThat(SafeDocumentDownloader.publicAddress(InetAddress.getByName(ip))).as(ip).isFalse();
    assertThat(SafeDocumentDownloader.publicAddress(InetAddress.getByName("8.8.8.8"))).isTrue();
    for (String url :
        List.of(
            "http://example.org/a.pdf",
            "file:///a.pdf",
            "https://a:b@example.org/file",
            "https://example.org:8443/file",
            "https://example.org/a#b"))
      assertThatThrownBy(() -> SafeDocumentDownloader.safeUri(url))
          .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void distinguishesImagesWithoutUploadingAndRejectsHtml() throws Exception {
    assertThat(
            NewsDocumentReader.inspect(
                    new SafeDocumentDownloader.Download(
                        "https://source/a",
                        "image/png",
                        new byte[] {(byte) 137, 80, 78, 71, 0, 0, 0, 0}))
                .image())
        .isTrue();
    assertThatThrownBy(
            () ->
                NewsDocumentReader.inspect(
                    new SafeDocumentDownloader.Download(
                        "https://source/a.pdf",
                        "text/html",
                        "<html>Access denied</html>".getBytes())))
        .hasMessage("UNSUPPORTED_DOCUMENT_TYPE");
    assertThatThrownBy(
            () ->
                NewsDocumentReader.inspect(
                    new SafeDocumentDownloader.Download(
                        "https://source/a.pdf",
                        "application/pdf",
                        "%PDF-1.7 /Encrypt %%EOF".getBytes())))
        .hasMessage("PDF_ENCRYPTED_ACTIVE_OR_INCOMPLETE");
  }

  @Test
  void officeCannotExpandArbitrarilyOrResolveExternalEntities() throws Exception {
    assertThatThrownBy(
            () ->
                NewsDocumentReader.inspect(
                    zip(
                        "word/document.xml",
                        "<!DOCTYPE x [<!ENTITY e SYSTEM 'file:///private'>]><x>&e;</x>")))
        .isInstanceOf(Exception.class);
    assertThatThrownBy(() -> NewsDocumentReader.inspect(zip("word/vbaProject.bin", "bad")))
        .hasMessage("UNSAFE_OFFICE_ARCHIVE");
    assertThatThrownBy(
            () -> NewsDocumentReader.inspect(zip("word/document.xml", "a".repeat(2_000_001))))
        .hasMessage("OFFICE_EXPANSION_LIMIT");
    var valid =
        NewsDocumentReader.inspect(
            zip("word/document.xml", "<document><p>Report 123</p></document>"));
    assertThat(valid.extension()).isEqualTo("docx");
    assertThat(valid.text()).contains("Report 123");
  }

  private SafeDocumentDownloader.Download zip(String name, String text) throws Exception {
    var buffer = new ByteArrayOutputStream();
    try (var zip = new ZipOutputStream(buffer)) {
      zip.putNextEntry(new ZipEntry(name));
      zip.write(text.getBytes(StandardCharsets.UTF_8));
      zip.closeEntry();
    }
    return new SafeDocumentDownloader.Download(
        "https://source/report.docx", "application/zip", buffer.toByteArray());
  }

  @Test
  void onlyProviderMetadataProvesRetrieval() throws Exception {
    assertThat(NewsRecoveryService.retrieved("{\"fetch_success\":true}")).isEmpty();
    var root = json.createObjectNode();
    var urls =
        root.putArray("candidates")
            .addObject()
            .putObject("urlContextMetadata")
            .putArray("urlMetadata");
    urls.addObject()
        .put("retrievedUrl", "https://source/a")
        .put("urlRetrievalStatus", "URL_RETRIEVAL_STATUS_SUCCESS");
    urls.addObject()
        .put("retrievedUrl", "https://source/b")
        .put("urlRetrievalStatus", "URL_RETRIEVAL_STATUS_ERROR");
    assertThat(NewsRecoveryService.retrieved(root.toString())).containsExactly("https://source/a");
  }

  @Test
  void binaryAuditNeverStoresBase64() {
    var request = json.createObjectNode();
    request
        .putArray("contents")
        .addObject()
        .putArray("parts")
        .addObject()
        .putObject("inlineData")
        .put("mimeType", "application/pdf")
        .put("data", Base64.getEncoder().encodeToString("test bytes".getBytes()));
    var sanitized = NewsRecoveryService.sanitized(request);
    assertThat(
            sanitized.path("contents").path(0).path("parts").path(0).path("inlineData").has("data"))
        .isFalse();
    assertThat(
            request.path("contents").path(0).path("parts").path(0).path("inlineData").has("data"))
        .isTrue();
  }

  @Test
  void missingBodyOrFabricatedQuotesAreBlocked() {
    var output =
        json.createObjectNode()
            .put("proposed_content", "a".repeat(250))
            .put("proposed_title", "Report")
            .put("assessment", "COMPLETE");
    output.putArray("evidence").addObject().put("quote", "not found in source");
    assertThat(NewsRecoveryValidation.contentIssues(output)).contains("RECOVERY_QUOTE_MISMATCH");
    output.put("proposed_content", "");
    assertThat(NewsRecoveryValidation.contentIssues(output))
        .contains("RECOVERY_BODY_MISSING_OR_OUT_OF_BOUNDS");
  }

  @Test
  void responseContractRejectsUnknownFields() throws Exception {
    try (var stream = new ClassPathResource("llm/news_content_recovery.json").getInputStream()) {
      var schema = json.readTree(stream).path("response_schema");
      assertThat(
              LlmSchemaValidator.validate(
                  schema, json.createObjectNode().put("fetch_success", true)))
          .isNotEmpty();
    }
  }
}
