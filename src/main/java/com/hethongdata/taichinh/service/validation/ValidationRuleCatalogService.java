package com.hethongdata.taichinh.service.validation;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.hethongdata.taichinh.entity.validation.ValidationRuleEntity;
import com.hethongdata.taichinh.repository.jpa.validation.ValidationRuleJpaRepository;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Service
public class ValidationRuleCatalogService {
    private final ValidationRuleJpaRepository rules;
    private final ObjectMapper objectMapper;

    public ValidationRuleCatalogService(
            ValidationRuleJpaRepository rules, ObjectMapper objectMapper) {
        this.rules = rules;
        this.objectMapper = objectMapper;
    }

    @Transactional
    public int seed() {
        definitions()
                .forEach(
                        definition ->
                                rules.findByCode(definition.code)
                                        .ifPresentOrElse(
                                                existing ->
                                                        existing.refresh(
                                                                definition.name,
                                                                definition.domain,
                                                                definition.severity,
                                                                definition.type,
                                                                config(definition.config),
                                                                definition.description,
                                                                definition.executor),
                                                () ->
                                                        rules.save(
                                                                ValidationRuleEntity.create(
                                                                        definition.code,
                                                                        definition.name,
                                                                        definition.domain,
                                                                        definition.severity,
                                                                        definition.type,
                                                                        config(definition.config),
                                                                        definition.description,
                                                                        definition.executor))));
        return definitions().size();
    }

    public List<ValidationRuleEntity> list() {
        return rules.findAll();
    }

    private JsonNode config(String json) {
        try {
            return objectMapper.readTree(json);
        } catch (Exception exception) {
            throw new IllegalStateException(
                    "Invalid built-in validation rule configuration", exception);
        }
    }

    private List<Definition> definitions() {
        return List.of(
                new Definition(
                        "PRICE_OHLC_VALID",
                        "OHLC price relationship",
                        "MARKET_PRICE",
                        "ERROR",
                        "BUSINESS",
                        "PRICE_OHLC_VALID",
                        "{\"fields\":[\"open\",\"high\",\"low\",\"close\"]}",
                        "Validates low <= open/close <= high."),
                new Definition(
                        "PRICE_NON_NEGATIVE",
                        "Non-negative market price",
                        "MARKET_PRICE",
                        "CRITICAL",
                        "RANGE",
                        "PRICE_NON_NEGATIVE",
                        "{\"minimum\":0}",
                        "Quarantines a payload containing a negative price."),
                new Definition(
                        "MARKET_VOLUME_NON_NEGATIVE",
                        "Non-negative market volume",
                        "MARKET_PRICE",
                        "CRITICAL",
                        "RANGE",
                        "MARKET_VOLUME_NON_NEGATIVE",
                        "{\"minimum\":0}",
                        "Quarantines a payload containing a negative trading volume."),
                new Definition(
                        "STATEMENT_REQUIRED_KEYS",
                        "Financial statement payload present",
                        "FINANCIAL_STATEMENT",
                        "ERROR",
                        "NOT_NULL",
                        "STATEMENT_REQUIRED_KEYS",
                        "{\"required\":[\"payload\"]}",
                        "Rejects an empty financial statement payload."),
                new Definition(
                        "STATEMENT_ITEM_CODE_REQUIRED",
                        "Financial statement item code",
                        "FINANCIAL_STATEMENT",
                        "ERROR",
                        "NOT_NULL",
                        "STATEMENT_ITEM_CODE_REQUIRED",
                        "{\"field\":\"itemCode\"}",
                        "Each financial statement item must have itemCode."),
                new Definition(
                        "NEWS_TITLE_REQUIRED",
                        "News title required",
                        "NEWS",
                        "ERROR",
                        "NOT_NULL",
                        "NEWS_TITLE_REQUIRED",
                        "{\"fields\":[\"title\",\"headline\"]}",
                        "Rejects news without a title/headline."),
                new Definition(
                        "NEWS_URL_REQUIRED",
                        "News source link required",
                        "NEWS",
                        "ERROR",
                        "FORMAT",
                        "NEWS_URL_REQUIRED",
                        "{\"fields\":[\"url\",\"link\",\"href\"],\"schemes\":[\"http\",\"https\"]}",
                        "Every news item must have a valid HTTP(S) source link."),
                new Definition(
                        "NEWS_DUPLICATE_HASH",
                        "News duplicate checksum",
                        "NEWS",
                        "WARNING",
                        "UNIQUE",
                        "NEWS_DUPLICATE_HASH",
                        "{\"strategy\":\"raw-checksum\"}",
                        "Records the duplicate policy; canonical deduplication remains a later mapping concern."),
                new Definition(
                        "RAW_ENVELOPE_REQUIRED",
                        "Data response envelope",
                        "RAW",
                        "ERROR",
                        "FORMAT",
                        "RAW_ENVELOPE_REQUIRED",
                        "{\"required\":[\"provider\",\"dataset\",\"retrieved_at\",\"data\"]}",
                        "Applies only to data-bearing responses; link-only payloads are not rejected by this rule."),
                new Definition(
                        "DATA_COUNT_MATCH",
                        "Raw response item count",
                        "RAW",
                        "WARNING",
                        "RECONCILIATION",
                        "DATA_COUNT_MATCH",
                        "{\"countField\":\"count\",\"dataField\":\"data\"}",
                        "When supplied, count must equal the number of data items."),
                new Definition(
                        "RAW_ERROR_MESSAGE",
                        "Upstream error payload",
                        "RAW",
                        "CRITICAL",
                        "CUSTOM",
                        "RAW_ERROR_MESSAGE",
                        "{\"markers\":[\"error\",\"errors\",\"failed\"]}",
                        "Quarantines a transport-success payload that carries an upstream error marker."));
    }

    private record Definition(
            String code,
            String name,
            String domain,
            String severity,
            String type,
            String executor,
            String config,
            String description) {}
}
