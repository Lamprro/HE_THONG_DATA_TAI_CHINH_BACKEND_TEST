package com.hethongdata.taichinh.integration.cloudinary;

import java.io.*;
import java.net.*;
import java.util.*;
import org.apache.hc.client5.http.DnsResolver;
import org.apache.hc.client5.http.classic.methods.HttpGet;
import org.apache.hc.client5.http.config.RequestConfig;
import org.apache.hc.client5.http.impl.classic.HttpClients;
import org.apache.hc.client5.http.impl.io.PoolingHttpClientConnectionManagerBuilder;
import org.apache.hc.core5.util.Timeout;
import org.springframework.stereotype.Component;

/** Bounded HTTPS fetch of explicit attachments only, never a Python/article retry. */
@Component
public class SafeDocumentDownloader {
  public record Download(String url, String mime, byte[] bytes) {}

  private final CloudinaryDocumentProperties properties;

  public SafeDocumentDownloader(CloudinaryDocumentProperties properties) {
    this.properties = properties;
  }

  public static URI safeUri(String value) {
    URI uri = URI.create(value);
    if (!"https".equalsIgnoreCase(uri.getScheme())
        || uri.getHost() == null
        || uri.getUserInfo() != null
        || (uri.getPort() != -1 && uri.getPort() != 443)
        || value.length() > 2048
        || uri.getFragment() != null) throw new IllegalArgumentException("UNSAFE_ATTACHMENT_URL");
    return uri;
  }

  public static boolean publicAddress(InetAddress ip) {
    if (ip.isAnyLocalAddress()
        || ip.isLoopbackAddress()
        || ip.isLinkLocalAddress()
        || ip.isSiteLocalAddress()
        || ip.isMulticastAddress()) return false;
    byte[] b = ip.getAddress();
    if (b.length == 16)
      return (b[0] & 0xe0) == 0x20; // only global unicast, reject ULA/mapped/NAT64
    int a = b[0] & 255, c = b[1] & 255;
    return a != 0
        && a != 127
        && a < 224
        && !(a == 100 && c >= 64 && c <= 127)
        && !(a == 169 && c == 254)
        && !(a == 192 && (c == 0 || c == 168))
        && !(a == 198 && (c == 18 || c == 19 || c == 51))
        && !(a == 203 && c == 0);
  }

  public Download fetch(String url) throws Exception {
    URI uri = safeUri(url);
    long deadline = System.nanoTime() + properties.readTimeout().toNanos();
    for (int redirect = 0; redirect <= 3; redirect++) {
      var addresses = InetAddress.getAllByName(uri.getHost());
      if (addresses.length == 0 || Arrays.stream(addresses).anyMatch(a -> !publicAddress(a)))
        throw new IllegalArgumentException("PRIVATE_ATTACHMENT_ADDRESS");
      final String host = uri.getHost();
      // Pin the checked DNS result for the actual socket. No second DNS resolution/rebinding.
      DnsResolver resolver =
          new DnsResolver() {
            public InetAddress[] resolve(String name) throws UnknownHostException {
              if (!host.equalsIgnoreCase(name)) throw new UnknownHostException("Unexpected host");
              return addresses.clone();
            }

            public String resolveCanonicalHostname(String name) {
              return name;
            }
          };
      var manager =
          PoolingHttpClientConnectionManagerBuilder.create().setDnsResolver(resolver).build();
      var config =
          RequestConfig.custom()
              .setConnectTimeout(Timeout.ofMilliseconds(properties.connectTimeout().toMillis()))
              .setResponseTimeout(Timeout.ofMilliseconds(properties.readTimeout().toMillis()))
              .setConnectionRequestTimeout(Timeout.ofSeconds(5))
              .build();
      try (var client =
          HttpClients.custom()
              .setConnectionManager(manager)
              .setDefaultRequestConfig(config)
              .disableRedirectHandling()
              .disableAutomaticRetries()
              .disableContentCompression()
              .build()) {
        var get = new HttpGet(uri);
        get.setHeader("User-Agent", "FinancialNewsDocumentReader/1.0");
        get.setHeader("Accept-Encoding", "identity");
        try (var response = client.execute(get)) {
          int status = response.getCode();
          if (Set.of(301, 302, 303, 307, 308).contains(status)) {
            var location = response.getFirstHeader("Location");
            if (location == null) throw new IOException("REDIRECT_WITHOUT_LOCATION");
            uri = safeUri(uri.resolve(location.getValue()).toString());
            continue;
          }
          if (status != 200) throw new IOException("DOCUMENT_HTTP_" + status);
          var entity = response.getEntity();
          if (entity == null) throw new IOException("DOCUMENT_EMPTY");
          long max = Math.min(properties.maxFileBytes(), 8L * 1024 * 1024);
          if (entity.getContentLength() > max) throw new IOException("DOCUMENT_TOO_LARGE");
          try (var stream = entity.getContent();
              var out = new ByteArrayOutputStream()) {
            byte[] block = new byte[8192];
            int read;
            while ((read = stream.read(block)) != -1) {
              if (System.nanoTime() > deadline) throw new IOException("DOCUMENT_DEADLINE");
              if ((long) out.size() + read > max) throw new IOException("DOCUMENT_TOO_LARGE");
              out.write(block, 0, read);
            }
            String mime =
                Optional.ofNullable(entity.getContentType())
                    .orElse("application/octet-stream")
                    .split(";")[0]
                    .trim()
                    .toLowerCase(Locale.ROOT);
            return new Download(uri.toString(), mime, out.toByteArray());
          }
        }
      }
    }
    throw new IOException("TOO_MANY_REDIRECTS");
  }
}
