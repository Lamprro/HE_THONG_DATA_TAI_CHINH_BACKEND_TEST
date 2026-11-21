package com.hethongdata.taichinh.entity.admin;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import lombok.Getter;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

@Entity
@Table(name = "admin_schedule_policy")
@Getter
public class AdminSchedulePolicyEntity {
    @Id
    private String id;

    private boolean enabled;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "job_codes")
    private String jobCodes;

    private long version;

    private String reason;

    @Column(name = "updated_at")
    private Instant updatedAt;

    @Column(name = "heartbeat_at")
    private Instant heartbeatAt;

    @Column(name = "last_sweep_started_at")
    private Instant lastSweepStartedAt;

    @Column(name = "last_sweep_finished_at")
    private Instant lastSweepFinishedAt;

    @Column(name = "last_error")
    private String lastError;

    protected AdminSchedulePolicyEntity() {}
}
