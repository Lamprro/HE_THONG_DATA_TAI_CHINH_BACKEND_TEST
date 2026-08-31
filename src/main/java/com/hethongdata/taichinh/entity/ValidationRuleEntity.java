package com.hethongdata.taichinh.entity;

import com.fasterxml.jackson.databind.JsonNode;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

@Entity
@Table(name = "validation_rules")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class ValidationRuleEntity {

    @Id
    @Column(name = "id")
    private Long id;

    @Column(name = "code")
    private String code;

    @Column(name = "name")
    private String name;

    @Column(name = "data_domain")
    private String dataDomain;

    @Column(name = "severity")
    private String severity;

    @Column(name = "rule_type")
    private String ruleType;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "rule_config")
    private JsonNode ruleConfig;

    @Column(name = "description")
    private String description;

    @Column(name = "is_active")
    private Boolean isActive;

    @Column(name = "created_at")
    private Instant createdAt;

    @Column(name = "updated_at")
    private Instant updatedAt;

    @Column(name = "executor_key")
    private String executorKey;

}
