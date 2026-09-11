package com.hethongdata.taichinh.entity.validation;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

import java.util.UUID;

class ValidationResultEntityTests {

    @Test
    void failedResultIsCreatedAsOpenIssue() {
        ValidationResultEntity result =
                ValidationResultEntity.create(
                        1L,
                        "PRICE_NON_NEGATIVE",
                        "CRITICAL",
                        UUID.randomUUID(),
                        UUID.randomUUID(),
                        "FAIL",
                        "price=-1",
                        ">= 0",
                        "Negative price is invalid");

        assertThat(result.getStatus()).isEqualTo("FAIL");
        assertThat(result.getRuleCode()).isEqualTo("PRICE_NON_NEGATIVE");
        assertThat(result.getSeverity()).isEqualTo("CRITICAL");
        assertThat(result.getHandlingStatus()).isEqualTo("OPEN");
        assertThat(result.getCheckedAt()).isNotNull();
    }

    @Test
    void successfulResultDoesNotRequireHandling() {
        ValidationResultEntity result =
                ValidationResultEntity.create(
                        1L,
                        "PRICE_NON_NEGATIVE",
                        "CRITICAL",
                        UUID.randomUUID(),
                        UUID.randomUUID(),
                        "PASS",
                        null,
                        null,
                        "No negative price found");

        assertThat(result.getStatus()).isEqualTo("PASS");
        assertThat(result.getHandlingStatus()).isEqualTo("NOT_REQUIRED");
    }
}
