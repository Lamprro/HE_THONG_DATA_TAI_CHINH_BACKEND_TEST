package com.hethongdata.taichinh.entity;

import com.fasterxml.jackson.databind.JsonNode;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.annotations.UuidGenerator;
import org.hibernate.type.SqlTypes;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "financial_statement_items")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class FinancialStatementItemEntity {

    @Id
    @UuidGenerator
    @Column(name = "id")
    private UUID id;

    @Column(name = "financial_statement_id")
    private UUID financialStatementId;

    @Column(name = "item_code")
    private String itemCode;

    @Column(name = "item_name")
    private String itemName;

    @Column(name = "parent_item_code")
    private String parentItemCode;

    @Column(name = "value")
    private BigDecimal value;

    @Column(name = "raw_value")
    private String rawValue;

    @Column(name = "unit")
    private String unit;

    @Column(name = "display_order")
    private Integer displayOrder;

    @Column(name = "is_total")
    private Boolean isTotal;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "metadata")
    private JsonNode metadata;

    @Column(name = "created_at")
    private Instant createdAt;
}
