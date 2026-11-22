package com.hethongdata.taichinh.service.validation;

import com.hethongdata.taichinh.entity.validation.DataVersionEntity;
import com.hethongdata.taichinh.entity.validation.ValidationResultEntity;
import com.hethongdata.taichinh.repository.jpa.validation.ValidationResultJpaRepository;
import com.hethongdata.taichinh.repository.jpa.validation.ValidationRuleJpaRepository;
import java.math.BigDecimal;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.http.HttpStatus;
import com.hethongdata.taichinh.repository.jpa.validation.DataVersionJpaRepository;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

/**
 * Persists a terminal data-version transition independently from a failed build transaction, and
 * selects a reviewed batch for a manual rebuild.
 */
@Service
public class DataVersionLifecycleService {
    private static final String CROSS_SOURCE_RULE = "CROSS_SOURCE_DIFFERENCE";
    private final DataVersionJpaRepository versions;
    private final ValidationRuleJpaRepository rules;
    private final ValidationResultJpaRepository results;

    public DataVersionLifecycleService(DataVersionJpaRepository versions,
            ValidationRuleJpaRepository rules, ValidationResultJpaRepository results) {
        this.versions = versions;
        this.rules = rules;
        this.results = results;
    }

    /** Choose a reviewed batch; the native writer still performs its locked, atomic validation. */
    public static DataVersionEntity requireActive(DataVersionJpaRepository versions, UUID id, String domain) {
        var v = versions.findById(id).orElseThrow(() -> new IllegalArgumentException("Data version not found"));
        if (!domain.equals(v.getDataDomain()) || !"ACTIVE".equals(v.getStatus()))
            throw new IllegalArgumentException("Requires an ACTIVE " + domain + " data version");
        return v;
    }

    public static void requireMatchingSource(UUID rawId, String key,
            BigDecimal candidate, BigDecimal reference, BigDecimal tolerance) {
        if (candidate == null || reference == null) return;
        var limit = reference.abs().multiply(tolerance);
        if (candidate.subtract(reference).abs().compareTo(limit) > 0) {
            throw new SourceConflict(rawId, key, candidate.toPlainString(), reference.toPlainString());
        }
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void holdConflict(UUID versionId, SourceConflict conflict) {
        var version = versions.findByIdForUpdate(versionId).orElseThrow();
        if (!"ACTIVE".equals(version.getStatus()) || version.getReviewedAt() != null) return;
        var rule = rules.findByCode(CROSS_SOURCE_RULE)
                .orElseThrow(() -> new IllegalStateException("Cross-source validation rule is missing"));
        results.save(ValidationResultEntity.create(rule.getId(), rule.getCode(), rule.getSeverity(),
                version.getIngestionRunId(), conflict.rawId, "FAIL",
                conflict.observed, conflict.expected, conflict.getMessage()));
        version.holdForReview(conflict.getMessage());
    }

    @Transactional
    public DataVersionEntity reviewSourceConflict(UUID versionId, UUID userId, boolean accept, String reason) {
        if (reason == null || reason.isBlank() || reason.length() > 2000) {
            throw new IllegalArgumentException("A reason (1..2000) is required");
        }
        var version = versions.findByIdForUpdate(versionId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Data version not found"));
        if (!"PENDING_REVIEW".equals(version.getStatus())) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Data version is no longer pending review");
        }
        var issues = results.findByIngestionRunIdAndRuleCodeAndHandlingStatus(
                version.getIngestionRunId(), CROSS_SOURCE_RULE, "OPEN");
        if (issues.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "No open cross-source issue for this version");
        }
        version.decideReview(userId, accept, reason);
        issues.forEach(issue -> issue.resolveSourceReview(userId, reason));
        return version;
    }

    public static final class SourceConflict extends RuntimeException {
        private final UUID rawId;
        private final String observed;
        private final String expected;

        private SourceConflict(UUID rawId, String key, String observed, String expected) {
            super(key + ": nguồn mới " + observed + ", nguồn hiện tại " + expected);
            this.rawId = rawId;
            this.observed = observed;
            this.expected = expected;
        }
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void rejectBuildFailure(UUID versionId, String workflow, Throwable failure) {
        DataVersionEntity version = versions.findByIdForUpdate(versionId)
                .orElseThrow(() -> new IllegalArgumentException("Không tìm thấy data version: " + versionId));
        if (!"ACTIVE".equals(version.getStatus())) {
            return;
        }
        String detail = failure == null || failure.getMessage() == null || failure.getMessage().isBlank()
                ? "Không xác định được chi tiết lỗi"
                : failure.getMessage();
        version.markRejected("Workflow " + workflow + " không thể ghi dữ liệu: " + detail);
        versions.save(version);
    }
}
