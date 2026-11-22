package com.hethongdata.taichinh.service.validation;

import com.hethongdata.taichinh.entity.validation.DataVersionEntity;
import com.hethongdata.taichinh.entity.validation.ValidationResultEntity;
import com.hethongdata.taichinh.entity.validation.ValidationRuleEntity;
import com.hethongdata.taichinh.repository.jpa.validation.DataVersionJpaRepository;
import com.hethongdata.taichinh.repository.jpa.validation.ValidationResultJpaRepository;
import com.hethongdata.taichinh.repository.jpa.validation.ValidationRuleJpaRepository;
import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class DataVersionLifecycleServiceTests {
    @Test
    void thresholdUsesComparableNormalizedValues() {
        var id = UUID.randomUUID();
        var limit = new BigDecimal("0.001");
        var current = new BigDecimal("100000");
        assertThatCode(() -> DataVersionLifecycleService.requireMatchingSource(id, "NET_SALES",
                new BigDecimal("100100"), current, limit)).doesNotThrowAnyException();
        assertThatThrownBy(() -> DataVersionLifecycleService.requireMatchingSource(id, "NET_SALES",
                new BigDecimal("100101"), current, limit)).isInstanceOf(DataVersionLifecycleService.SourceConflict.class);
        assertThatThrownBy(() -> DataVersionLifecycleService.requireMatchingSource(id, "Close",
                new BigDecimal("10.01"), new BigDecimal("10"), BigDecimal.ZERO))
                .isInstanceOf(DataVersionLifecycleService.SourceConflict.class);
        assertThatCode(() -> DataVersionLifecycleService.requireMatchingSource(id, "Close",
                new BigDecimal("10.0"), new BigDecimal("10.00"), BigDecimal.ZERO)).doesNotThrowAnyException();
    }

    @Test
    void holdsOneVersionAndResolvesItsIssueOnlyWhenAnAdminDecides() {
        var versions = mock(DataVersionJpaRepository.class);
        var rules = mock(ValidationRuleJpaRepository.class);
        var results = mock(ValidationResultJpaRepository.class);
        var service = new DataVersionLifecycleService(versions, rules, results);
        var versionId = UUID.randomUUID();
        var sourceRun = UUID.randomUUID();
        var rawId = UUID.randomUUID();
        var operator = UUID.randomUUID();
        var version = DataVersionEntity.acceptedForRun("FINANCIAL_STATEMENT", sourceRun, 1, "x".repeat(64));
        var rule = mock(ValidationRuleEntity.class);
        when(rule.getId()).thenReturn(15L);
        when(rule.getCode()).thenReturn("CROSS_SOURCE_DIFFERENCE");
        when(rule.getSeverity()).thenReturn("WARNING");
        when(versions.findByIdForUpdate(versionId)).thenReturn(Optional.of(version));
        when(rules.findByCode("CROSS_SOURCE_DIFFERENCE")).thenReturn(Optional.of(rule));

        var error = catchThrowableOfType(
                () -> DataVersionLifecycleService.requireMatchingSource(rawId, "FPT NET_SALES",
                        BigDecimal.TEN, BigDecimal.ONE, BigDecimal.ZERO), DataVersionLifecycleService.SourceConflict.class);
        service.holdConflict(versionId, error);
        service.holdConflict(versionId, error);
        assertThat(version.getStatus()).isEqualTo("PENDING_REVIEW");
        var capture = ArgumentCaptor.forClass(ValidationResultEntity.class);
        verify(results).save(capture.capture());
        var issue = capture.getValue();
        assertThat(issue.getObservedValue()).isEqualTo("10");
        assertThat(issue.getExpectedValue()).isEqualTo("1");
        assertThat(issue.getRawPayloadId()).isEqualTo(rawId);
        when(results.findByIngestionRunIdAndRuleCodeAndHandlingStatus(sourceRun, "CROSS_SOURCE_DIFFERENCE", "OPEN"))
                .thenReturn(List.of(issue));
        service.reviewSourceConflict(versionId, operator, true, "Verified the source");
        assertThat(version.getStatus()).isEqualTo("ACTIVE");
        assertThat(version.getReviewedByUserId()).isEqualTo(operator);
        assertThat(issue.getStatus()).isEqualTo("FAIL");
        assertThat(issue.getHandlingStatus()).isEqualTo("RESOLVED");
    }

    @Test
    void rejectingTheCandidateDoesNotMakeItEligibleForBuild() {
        var version = DataVersionEntity.acceptedForRun("MARKET_PRICE", UUID.randomUUID(), 1, "x".repeat(64));
        version.holdForReview("Closing price differs");
        version.decideReview(UUID.randomUUID(), false, "Keep existing price");
        assertThat(version.getStatus()).isEqualTo("REJECTED");
        assertThatThrownBy(version::markActivated).isInstanceOf(IllegalStateException.class);
    }
}
