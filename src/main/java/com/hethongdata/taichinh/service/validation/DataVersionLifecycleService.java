package com.hethongdata.taichinh.service.validation;

import com.hethongdata.taichinh.entity.validation.DataVersionEntity;
import com.hethongdata.taichinh.repository.jpa.validation.DataVersionJpaRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

/** Persists terminal version transitions independently from a failed build transaction. */
@Service
public class DataVersionLifecycleService {
    private final DataVersionJpaRepository versions;

    public DataVersionLifecycleService(DataVersionJpaRepository versions) {
        this.versions = versions;
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void rejectBuildFailure(UUID versionId, String workflow, Throwable failure) {
        DataVersionEntity version = versions.findByIdForUpdate(versionId)
                .orElseThrow(() -> new IllegalArgumentException("Không tìm thấy data version: " + versionId));
        if (!"ACTIVE".equals(version.getStatus())) return;
        String detail = failure == null || failure.getMessage() == null
                ? "Không xác định được chi tiết lỗi"
                : failure.getMessage();
        version.markRejected("Workflow " + workflow + " không thể ghi dữ liệu: " + detail);
        versions.save(version);
    }
}
