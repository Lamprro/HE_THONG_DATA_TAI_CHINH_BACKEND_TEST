package com.hethongdata.taichinh.repository.jpa.admin;

import com.hethongdata.taichinh.entity.admin.AdminSchedulePolicyEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.transaction.annotation.Transactional;

public interface AdminScheduleJpaRepository extends JpaRepository<AdminSchedulePolicyEntity, String> {
    @Modifying
    @Transactional
    @Query(value = """
        update admin_schedule_policy set enabled=:enabled, job_codes=cast(:jobCodesJson as jsonb),
            version=version+1, reason=:reason, updated_at=now()
        where id='INGESTION' and version=:version
        """, nativeQuery = true)
    int updatePolicy(boolean enabled, String jobCodesJson, String reason, long version);

    @Modifying
    @Transactional
    @Query(value = "update admin_schedule_policy set heartbeat_at=now() where id='INGESTION'", nativeQuery = true)
    void updateHeartbeat();

    @Modifying
    @Transactional
    @Query(value = """
        update admin_schedule_policy set last_sweep_started_at=now(), last_error=null where id='INGESTION'
        """, nativeQuery = true)
    void recordSweepStarted();

    @Modifying
    @Transactional
    @Query(value = """
        update admin_schedule_policy set last_sweep_finished_at=now() where id='INGESTION'
        """, nativeQuery = true)
    void recordSweepFinished();

    @Modifying
    @Transactional
    @Query(value = "update admin_schedule_policy set last_error=:error where id='INGESTION'", nativeQuery = true)
    void recordSweepError(String error);
}
