package com.hethongdata.taichinh.integration.cloudinary;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.*;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.*;
import org.springframework.stereotype.Component;

/** Authenticated raw storage. No public CDN URLs or credentials in the returned manifest. */
@Component
public class CloudinaryDocumentStorage {
  public record Asset(
      String assetId, String publicId, String sha256, long bytes, String deliveryType) {}

  private final CloudinaryDocumentProperties properties;
  private final ObjectMapper json;

  public CloudinaryDocumentStorage(CloudinaryDocumentProperties properties, ObjectMapper json) {
    this.properties = properties;
    this.json = json;
  }

  public boolean configured() {
    return properties.enabled() && properties.credentialsPresent();
  }

  public static String digest(byte[] bytes) {
    try {
      return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
    } catch (Exception e) {
      throw new IllegalStateException(e);
    }
  }

  public Asset upload(byte[] bytes, String extension) throws Exception {
    if (!configured()) throw new IllegalStateException("CLOUDINARY_CONFIGURATION_REQUIRED");
    if (!Set.of("pdf", "docx", "xlsx", "txt", "csv").contains(extension)
        || bytes.length == 0
        || bytes.length > properties.maxFileBytes())
      throw new IllegalArgumentException("DOCUMENT_STORAGE_POLICY");
    String hash = digest(bytes);
    var params = new TreeMap<String, String>();
    params.put("public_id", properties.folder() + "/" + hash + "." + extension);
    params.put("overwrite", "false");
    params.put("type", "authenticated");
    params.put("timestamp", Long.toString(Instant.now().getEpochSecond()));
    String signature =
        digest(
            (params.entrySet().stream()
                        .map(e -> e.getKey() + "=" + e.getValue())
                        .collect(java.util.stream.Collectors.joining("&"))
                    + properties.apiSecret())
                .getBytes(StandardCharsets.UTF_8));
    params.put("api_key", properties.apiKey());
    params.put("signature", signature);
    // Bounded files; data URI avoids temporary files and multipart filename injection.
    params.put(
        "file",
        "data:application/octet-stream;base64," + Base64.getEncoder().encodeToString(bytes));
    String body =
        params.entrySet().stream()
            .map(
                e ->
                    URLEncoder.encode(e.getKey(), StandardCharsets.UTF_8)
                        + "="
                        + URLEncoder.encode(e.getValue(), StandardCharsets.UTF_8))
            .collect(java.util.stream.Collectors.joining("&"));
    var request =
        HttpRequest.newBuilder(
                URI.create(
                    "https://api.cloudinary.com/v1_1/" + properties.cloudName() + "/raw/upload"))
            .timeout(properties.readTimeout())
            .header("Content-Type", "application/x-www-form-urlencoded")
            .POST(HttpRequest.BodyPublishers.ofString(body))
            .build();
    try (var client = HttpClient.newBuilder().connectTimeout(properties.connectTimeout()).build()) {
      var response = client.send(request, HttpResponse.BodyHandlers.ofString());
      if (response.statusCode() != 200)
        throw new IllegalStateException("CLOUDINARY_HTTP_" + response.statusCode());
      var result = json.readTree(response.body());
      if (!params.get("public_id").equals(result.path("public_id").asText())
          || !result.hasNonNull("asset_id"))
        throw new IllegalStateException("CLOUDINARY_RESPONSE_INVALID");
      return new Asset(
          result.path("asset_id").asText(),
          params.get("public_id"),
          hash,
          bytes.length,
          "authenticated");
    }
  }
}
