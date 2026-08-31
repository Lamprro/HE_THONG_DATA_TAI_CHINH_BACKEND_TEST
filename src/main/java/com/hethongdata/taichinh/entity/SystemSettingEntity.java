package com.hethongdata.taichinh.entity;

import com.fasterxml.jackson.databind.JsonNode;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

@Entity
@Table(name = "system_settings")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class SystemSettingEntity {

    @Id
    @Column(name = "key")
    private String key;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "value")
    private JsonNode value;

    @Column(name = "description")
    private String description;

    @Column(name = "is_secret")
    private Boolean isSecret;

    @Column(name = "updated_by_user_id")
    private UUID updatedByUserId;

    @Column(name = "updated_at")
    private Instant updatedAt;

}
