package com.hethongdata.taichinh.entity.admin;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;
import lombok.Getter;

@Entity
@Table(name = "admin_operation_events")
@Getter
public class AdminOperationEventEntity {
    @Id
    private UUID id;

    @Column(name = "target_id")
    private UUID targetId;

    @Column(name = "event_type")
    private String eventType;

    private String reason;

    private String principal;

    @Column(name = "created_at")
    private Instant createdAt;

    protected AdminOperationEventEntity() {}
}
