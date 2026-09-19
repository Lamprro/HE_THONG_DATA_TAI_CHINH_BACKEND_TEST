package com.hethongdata.taichinh.service.validation;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.hethongdata.taichinh.entity.ingestion.RawPayloadEntity;
import com.hethongdata.taichinh.entity.ingestion.DataSourceEntity;
import com.hethongdata.taichinh.entity.validation.ValidationRuleEntity;
import com.hethongdata.taichinh.repository.jpa.ingestion.RawPayloadJpaRepository;
import com.hethongdata.taichinh.repository.jpa.MarketIndexJpaRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import java.util.UUID;
import java.util.stream.Stream;
import java.util.stream.StreamSupport;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

class NewsValidationRulesTests {
    private static final ObjectMapper JSON = new ObjectMapper();
    private final ValidationRuleExecutionService executor =
            new ValidationRuleExecutionService(
                    mock(RawPayloadJpaRepository.class),
                    mock(MarketIndexJpaRepository.class),
                    mock(com.hethongdata.taichinh.repository.jpa.master.SecurityJpaRepository.class));

    static Stream<String> catalogCodes() throws Exception {
        try (var input = NewsValidationRulesTests.class.getResourceAsStream("/validation/news-rules.json")) {
            return StreamSupport.stream(JSON.readTree(input).spliterator(), false)
                    .map(rule -> rule.path("code").asText()).toList().stream();
        }
    }

    static ValidationRuleEntity rule(String code) throws Exception {
        try (var input = NewsValidationRulesTests.class.getResourceAsStream("/validation/news-rules.json")) {
            for (JsonNode row : JSON.readTree(input))
                if (code.equals(row.path("code").asText()))
                    return ValidationRuleEntity.create(code, row.path("name").asText(),
                            row.path("domain").asText(), row.path("severity").asText(),
                            row.path("type").asText(), row.path("config"),
                            row.path("description").asText(), row.path("executor").asText());
        }
        throw new IllegalArgumentException(code);
    }

    private RawPayloadEntity raw(String type, String payload) throws Exception {
        RawPayloadEntity raw = RawPayloadEntity.create(
                null, null, null, null, null, null, null, null, null, null, null, null);
        raw.setId(UUID.randomUUID());
        raw.setEntityType(type);
        raw.setPayload(JSON.readTree(payload));
        raw.setSourceSymbol("FPT");
        raw.setChecksumSha256("a".repeat(64));
        DataSourceEntity source = mock(DataSourceEntity.class);
        when(source.getId()).thenReturn(12L);
        raw.setDataSource(source);
        return raw;
    }

    @ParameterizedTest
    @MethodSource("catalogCodes")
    void everyCatalogExecutorAcceptsItsValidDataShape(String code) throws Exception {
        ValidationRuleEntity rule = rule(code);
        RawPayloadEntity raw = raw(rule.getDataDomain(),
                "NEWS".equals(rule.getDataDomain())
                        ? """
                          {"data":[{"url":"https://cafef.vn/news.chn","title":"FPT news",
                          "symbol":"FPT","publishedAt":"10/09/2026 20:17"}],"count":1}
                          """
                        : """
                          {"requested_url":"https://cafef.vn/news.chn","final_url":"https://cafef.vn/news.chn",
                          "http_status":200,"textual":true}
                          """);
        raw.setContentType("text/html; charset=utf-8");
        raw.setRawText("<!DOCTYPE html><html><head><title>FPT news</title></head><body><p>Article content</p></body></html>");
        assertThat(executor.execute(rule, raw).status()).isEqualTo("PASS");
    }

    static Stream<String[]> invalidCases() {
        return Stream.of(
                new String[]{"NEWS_PAYLOAD_STRUCTURE", "{\"data\":[null]}"},
                new String[]{"NEWS_PAYLOAD_STRUCTURE", "{\"data\":[]}"},
                new String[]{"NEWS_PAYLOAD_STRUCTURE", "{\"data\":{}}"},
                new String[]{"NEWS_URL_REQUIRED", "{\"data\":[{\"url\":\"https:broken\"}]}"},
                new String[]{"NEWS_URL_REQUIRED", "{\"data\":[{\"url\":\"https://example.com\"},null]}"},
                new String[]{"NEWS_URL_REQUIRED", "{\"data\":[{\"url\":\"ftp://example.com\"}]}"},
                new String[]{"NEWS_URL_REQUIRED", "{\"data\":[{\"url\":\"https://user:pass@example.com\"}]}"},
                new String[]{"NEWS_TITLE_REQUIRED", "{\"data\":[{\"url\":\"https://example.com\"}]}"},
                new String[]{"NEWS_PUBLISHED_AT_VALID", "{\"data\":[{\"publishedAt\":\"31/02/2026 20:17\"}]}"},
                new String[]{"NEWS_PUBLISHED_AT_VALID", "{\"data\":[{\"publishedAt\":123}]}"},
                new String[]{"NEWS_SYMBOL_MATCH", "{\"data\":[{\"symbol\":\"HPG\"}]}"},
                new String[]{"NEWS_DATA_METADATA_REQUIRED", "{\"textual\":\"true\",\"http_status\":200}"},
                new String[]{"NEWS_DATA_URL_VALID", "{\"requested_url\":\"https://example.com\",\"final_url\":\"/relative\"}"},
                new String[]{"NEWS_DATA_HTTP_SUCCESS", "{\"http_status\":404}"},
                new String[]{"NEWS_DATA_HTTP_SUCCESS", "{\"http_status\":500}"},
                new String[]{"NEWS_DATA_HTTP_SUCCESS", "{\"http_status\":\"200\"}"},
                new String[]{"NEWS_DATA_HTTP_SUCCESS", "{\"http_status\":200.5}"},
                new String[]{"NEWS_DATA_HTTP_SUCCESS", "{\"http_status\":4294967496}"});
    }

    @ParameterizedTest
    @MethodSource("invalidCases")
    void rejectsMalformedOrFailedSourceData(String code, String payload) throws Exception {
        ValidationRuleEntity rule = rule(code);
        assertThat(executor.execute(rule, raw(rule.getDataDomain(), payload)).status()).isEqualTo("FAIL");
    }

    @Test
    void duplicateUrlsIgnoreTrackingButKeepArticleIdentifyingParameters() throws Exception {
        RawPayloadEntity raw = raw("NEWS", """
                {"data":[{"url":"https://EXAMPLE.com:443/news?id=1&utm_source=a"},
                         {"url":"https://example.com/news?utm_source=b&id=1#anchor"}]}
                """);
        var outcome = executor.execute(rule("NEWS_URL_DUPLICATE_IN_BATCH"), raw);
        assertThat(outcome.status()).isEqualTo("FAIL");
        assertThat(outcome.observed()).contains("[1]");
        raw.setPayload(JSON.readTree("""
                {"data":[{"url":"https://example.com/news?id=1"},{"url":"https://example.com/news?id=2"}]}
                """));
        assertThat(executor.execute(rule("NEWS_URL_DUPLICATE_IN_BATCH"), raw).status()).isEqualTo("PASS");
    }

    @Test
    void readsCustomConfigForFieldsAndHttpRange() throws Exception {
        var rule = rule("NEWS_URL_REQUIRED");
        ((ObjectNode) rule.getRuleConfig()).putArray("fields").add("article_url");
        assertThat(executor.execute(rule, raw("NEWS", """
                {"data":[{"article_url":"https://example.com/article"}]}
                """)).status()).isEqualTo("PASS");
        var http = rule("NEWS_DATA_HTTP_SUCCESS");
        ((ObjectNode) http.getRuleConfig()).put("maximum", 202);
        assertThat(executor.execute(http, raw("NEWS_DATA", "{\"http_status\":204}")).status()).isEqualTo("FAIL");
    }

    @Test
    void rejectsBlankNonHtmlAndScriptOnlyBodies() throws Exception {
        RawPayloadEntity raw = raw("NEWS_DATA", "{}");
        raw.setRawText("   ");
        assertThat(executor.execute(rule("NEWS_DATA_RAW_TEXT_REQUIRED"), raw).status()).isEqualTo("FAIL");
        raw.setRawText("{\"error\":\"not html\"}");
        assertThat(executor.execute(rule("NEWS_DATA_HTML_STRUCTURE"), raw).status()).isEqualTo("FAIL");
        raw.setRawText("<html><body><script>console.log('test')</script></body></html>");
        assertThat(executor.execute(rule("NEWS_DATA_HTML_STRUCTURE"), raw).status()).isEqualTo("FAIL");
    }

    @Test
    void detectsChallengeHeadingWithoutFlaggingNormalScriptsOrArticleMentions() throws Exception {
        RawPayloadEntity raw = raw("NEWS_DATA", "{}");
        raw.setRawText("<html><head><title>Just a moment...</title></head><body>Wait</body></html>");
        assertThat(executor.execute(rule("NEWS_DATA_BLOCK_PAGE_DETECTED"), raw).status()).isEqualTo("FAIL");
        raw.setRawText("<html><head><title>News about technology</title></head><body><script>var error=1;</script><p>This article discusses access denied messages.</p></body></html>");
        assertThat(executor.execute(rule("NEWS_DATA_BLOCK_PAGE_DETECTED"), raw).status()).isEqualTo("PASS");
    }

    @Test
    void binaryResponseCannotPassContentTypeRule() throws Exception {
        RawPayloadEntity raw = raw("NEWS_DATA", "{\"textual\":true}");
        raw.setContentType("application/pdf");
        assertThat(executor.execute(rule("NEWS_DATA_CONTENT_TYPE_VALID"), raw).status()).isEqualTo("FAIL");
        raw.setContentType("text/html");
        raw.setPayload(JSON.readTree("{\"textual\":false}"));
        assertThat(executor.execute(rule("NEWS_DATA_CONTENT_TYPE_VALID"), raw).status()).isEqualTo("FAIL");
    }
}
