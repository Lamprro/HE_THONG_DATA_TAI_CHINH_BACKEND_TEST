package com.hethongdata.taichinh.service.validation;

import com.hethongdata.taichinh.entity.validation.DataVersionEntity;
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
    private final DataVersionJpaRepository versions;

    public DataVersionLifecycleService(DataVersionJpaRepository versions) {
        this.versions = versions;
    }

    /** Select a reviewed batch; the native writer still performs its locked, atomic validation. */
    public static DataVersionEntity requireActive(DataVersionJpaRepository versions, UUID id, String domain) {
        var v = versions.findById(id).orElseThrow(() -> new IllegalArgumentException("Data version not found"));
        if (!domain.equals(v.getDataDomain()) || !"ACTIVE".equals(v.getStatus()))
            throw new IllegalArgumentException("Requires an ACTIVE " + domain + " data version");
        return v;
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
