package com.hethongdata.taichinh.entity.validation;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

import java.util.UUID;

class DataVersionEntityTests {

    @Test
    void marksValidatedBatchActivatedOnlyWhenConsumed() {
        DataVersionEntity version =
                DataVersionEntity.acceptedForRun("NEWS", UUID.randomUUID(), 1, "a".repeat(64));

        assertThat(version.getStatus()).isEqualTo("ACTIVE");
        assertThat(version.getActivatedAt()).isNull();

        version.markActivated();

        assertThat(version.getStatus()).isEqualTo("ACTIVATED");
        assertThat(version.getActivatedAt()).isNotNull();
    }

    @Test
    void rejectsActiveBatchAndRecordsBuildFailure() {
        DataVersionEntity version =
                DataVersionEntity.acceptedForRun("MARKET_PRICE", UUID.randomUUID(), 1, "a".repeat(64));

        version.markRejected("Workflow MARKET_PRICE_BUILD không thể ghi dữ liệu: invalid symbol");

        assertThat(version.getStatus()).isEqualTo("REJECTED");
        assertThat(version.getEffectiveTo()).isNotNull();
        assertThat(version.getNotes()).contains("MARKET_PRICE_BUILD", "invalid symbol");
        assertThat(version.getActivatedAt()).isNull();
    }
}
