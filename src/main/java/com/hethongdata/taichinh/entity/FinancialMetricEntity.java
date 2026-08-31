package com.hethongdata.taichinh.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.UuidGenerator;

@Entity
@Table(name = "financial_metrics")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class FinancialMetricEntity {

    @Id
    @UuidGenerator
    @Column(name = "id")
    private UUID id;

    @Column(name = "company_id")
    private UUID companyId;

    @Column(name = "security_id")
    private UUID securityId;

    @Column(name = "financial_period_id")
    private UUID financialPeriodId;

    @Column(name = "metric_definition_id")
    private Long metricDefinitionId;

    @Column(name = "as_of_date")
    private LocalDate asOfDate;

    @Column(name = "value")
    private BigDecimal value;

    @Column(name = "value_text")
    private String valueText;

    @Column(name = "data_source_id")
    private Long dataSourceId;

    @Column(name = "raw_payload_id")
    private UUID rawPayloadId;

    @Column(name = "data_version_id")
    private UUID dataVersionId;

    @Column(name = "is_derived")
    private Boolean isDerived;

    @Column(name = "is_canonical")
    private Boolean isCanonical;

    @Column(name = "calculation_version")
    private String calculationVersion;

    @Column(name = "quality_status")
    private String qualityStatus;

    @Column(name = "created_at")
    private Instant createdAt;

}
