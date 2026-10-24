package com.hethongdata.taichinh.service.financial;
import com.hethongdata.taichinh.integration.python.*;
import com.hethongdata.taichinh.application.port.model.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.web.client.RestClient;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.http.MediaType;
import org.junit.jupiter.api.Test;
import java.util.*;
import static org.assertj.core.api.Assertions.*;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.*;
import static org.springframework.test.web.client.response.MockRestResponseCreators.*;
class FinancialDisclosureAdapterTests {
 @Test void compressedListingIsDecodedBeforeUtf8Persistence() throws Exception {
  var data=new java.io.ByteArrayOutputStream();try(var zip=new java.util.zip.GZIPOutputStream(data)){zip.write("<html>source listing</html>".getBytes(java.nio.charset.StandardCharsets.UTF_8));}
  var builder=RestClient.builder();var server=MockRestServiceServer.bindTo(builder).build();var adapter=new PythonExternalFinancialDataAdapter(builder.build(),new PythonFinancialDataProperties(),new SensitiveHeaderSanitizer());server.expect(anything()).andRespond(withSuccess(data.toByteArray(),MediaType.TEXT_HTML).header("Content-Encoding","gzip"));var r=adapter.fetch(new ExternalFetchRequest(ExternalOperation.RAW_PROXY,"fpt-ir","FPT",null,null,null,Map.of("upstream_path","vi/nha-dau-tu/thong-tin-cong-bo")));assertThat(r.rawBody()).isEqualTo("<html>source listing</html>");server.verify();
 }
 @Test void binaryPdfIsByteExactAndHasOriginalChecksum() throws Exception {
  var builder=RestClient.builder();var server=MockRestServiceServer.bindTo(builder).build();var adapter=new PythonExternalFinancialDataAdapter(builder.build(),new PythonFinancialDataProperties(),new SensitiveHeaderSanitizer());
  byte[] bytes=new byte[]{37,80,68,70,45,49,46,55,10,0,-1,-2,127};
  server.expect(requestTo("https://he-thong-data-tai-chinh-test.vercel.app/api/v1/proxy/fpt-ir/api/media/report.pdf")).andRespond(withSuccess(bytes,MediaType.APPLICATION_PDF));
  var r=adapter.fetch(new ExternalFetchRequest(ExternalOperation.RAW_PROXY,"fpt-ir","FPT",null,null,null,Map.of("upstream_path","api/media/report.pdf")));var p=new ObjectMapper().readTree(r.rawBody());
  assertThat(r.contentType()).isEqualTo("application/json");assertThat(p.path("schema_version").asText()).isEqualTo("financial.disclosure.binary.v1");assertThat(Base64.getDecoder().decode(p.path("content_base64").asText())).containsExactly(bytes);assertThat(p.path("source_sha256").asText()).isEqualTo(HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(bytes)));server.verify();
 }
 @Test void htmlListingRemainsActualHtmlNotFakeFinancialStatement() {
  var builder=RestClient.builder();var server=MockRestServiceServer.bindTo(builder).build();var adapter=new PythonExternalFinancialDataAdapter(builder.build(),new PythonFinancialDataProperties(),new SensitiveHeaderSanitizer());
  server.expect(anything()).andRespond(withSuccess("<html>actual listing</html>",MediaType.TEXT_HTML));var r=adapter.fetch(new ExternalFetchRequest(ExternalOperation.RAW_PROXY,"fpt-ir","FPT",null,null,null,Map.of("upstream_path","vi/nha-dau-tu/thong-tin-cong-bo")));assertThat(r.contentType()).startsWith("text/html");assertThat(r.rawBody()).isEqualTo("<html>actual listing</html>");server.verify();
 }
 @Test void unknownProxyProviderStillRejected() {
  var adapter=new PythonExternalFinancialDataAdapter(RestClient.create(),new PythonFinancialDataProperties(),new SensitiveHeaderSanitizer());assertThatThrownBy(()->adapter.resolveUri(new ExternalFetchRequest(ExternalOperation.RAW_PROXY,"arbitrary-host","FPT",null,null,null,Map.of("upstream_path","private")))).isInstanceOf(IllegalArgumentException.class);
 }
}
