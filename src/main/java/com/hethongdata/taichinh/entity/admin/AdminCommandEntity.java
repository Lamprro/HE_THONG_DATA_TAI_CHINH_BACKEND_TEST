package com.hethongdata.taichinh.entity.admin;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;
import lombok.Getter;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

@Entity
@Table(name = "admin_commands")
@Getter
public class AdminCommandEntity {
    @Id
    private UUID id;

    @Column(name = "idempotency_key")
    private UUID idempotencyKey;

    @Column(name = "request_hash")
    private String requestHash;

    private String action;

    @Column(name = "resource_key")
    private String resourceKey;

    private String reason;

    private String principal;

    private String status;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "request_json")
    private String requestJson;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "result_json")
    private String resultJson;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "progress_json")
    private String progressJson;

    @Column(name = "parent_command_id")
    private UUID parentCommandId;

    @Column(name = "error_message")
    private String errorMessage;

    @Column(name = "created_at")
    private Instant createdAt;

    @Column(name = "started_at")
    private Instant startedAt;

    @Column(name = "finished_at")
    private Instant finishedAt;

    @Column(name = "acknowledgement_note")
    private String acknowledgementNote;

    protected AdminCommandEntity() {}
}
