package com.hethongdata.taichinh.service.validation;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.hethongdata.taichinh.entity.validation.ValidationRuleEntity;
import com.hethongdata.taichinh.repository.jpa.validation.ValidationRuleJpaRepository;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.ArrayList;
import java.io.IOException;
import org.springframework.core.io.ClassPathResource;

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
        List<Definition> all = definitions();
        all
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
        return all.size();
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
        List<Definition> all = new ArrayList<>(List.of(
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
                        "Records an open validation result for a payload containing a negative price."),
                new Definition(
                        "MARKET_VOLUME_NON_NEGATIVE",
                        "Non-negative market volume",
                        "MARKET_PRICE",
                        "CRITICAL",
                        "RANGE",
                        "MARKET_VOLUME_NON_NEGATIVE",
                        "{\"minimum\":0}",
                        "Records an open validation result for a payload containing a negative trading volume."),
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
                        "Records an open validation result for a transport-success payload that carries an upstream error marker.")));
        all.addAll(newsDefinitions());
        return List.copyOf(all);
    }

    /** Shared with the controlled DB sync; rule configs are JSON objects, not executable code. */
    private List<Definition> newsDefinitions() {
        try (var input = new ClassPathResource("validation/news-rules.json").getInputStream()) {
            JsonNode entries = objectMapper.readTree(input);
            List<Definition> definitions = new ArrayList<>();
            for (JsonNode entry : entries) {
                definitions.add(new Definition(
                        entry.required("code").asText(), entry.required("name").asText(),
                        entry.required("domain").asText(), entry.required("severity").asText(),
                        entry.required("type").asText(), entry.required("executor").asText(),
                        entry.required("config").toString(), entry.required("description").asText()));
            }
            return List.copyOf(definitions);
        } catch (IOException exception) {
            throw new IllegalStateException("Cannot read NEWS validation rule catalog", exception);
        }
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
