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
    void rejectsFailedDownstreamBatchAndRecordsTheReason() {
        DataVersionEntity version =
                DataVersionEntity.acceptedForRun(
                        "FINANCIAL_STATEMENT", UUID.randomUUID(), 1, "b".repeat(64));

        version.markRejected("Không thể ghi báo cáo tài chính");

        assertThat(version.getStatus()).isEqualTo("REJECTED");
        assertThat(version.getEffectiveTo()).isNotNull();
        assertThat(version.getActivatedAt()).isNull();
        assertThat(version.getNotes()).isEqualTo("Không thể ghi báo cáo tài chính");
    }
}
