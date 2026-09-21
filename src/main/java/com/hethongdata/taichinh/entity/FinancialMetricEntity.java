package com.hethongdata.taichinh.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

import org.hibernate.annotations.UuidGenerator;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

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

    public static FinancialMetricEntity provider(UUID companyId, UUID securityId, Long definitionId,
            LocalDate asOfDate, BigDecimal value, Long dataSourceId, UUID rawPayloadId, UUID dataVersionId) {
        FinancialMetricEntity entity = base(companyId, securityId, null, definitionId, asOfDate,
                value, dataSourceId, false, null);
        entity.rawPayloadId = rawPayloadId;
        entity.dataVersionId = dataVersionId;
        return entity;
    }

    public static FinancialMetricEntity derived(UUID companyId, UUID securityId, UUID periodId,
            Long definitionId, LocalDate asOfDate, BigDecimal value, Long dataSourceId,
            String calculationVersion) {
        return base(companyId, securityId, periodId, definitionId, asOfDate, value,
                dataSourceId, true, calculationVersion);
    }

    private static FinancialMetricEntity base(UUID companyId, UUID securityId, UUID periodId,
            Long definitionId, LocalDate asOfDate, BigDecimal value, Long dataSourceId,
            boolean derived, String calculationVersion) {
        FinancialMetricEntity entity = new FinancialMetricEntity();
        entity.companyId = companyId;
        entity.securityId = securityId;
        entity.financialPeriodId = periodId;
        entity.metricDefinitionId = definitionId;
        entity.asOfDate = asOfDate;
        entity.value = value;
        entity.dataSourceId = dataSourceId;
        entity.isDerived = derived;
        entity.isCanonical = false;
        entity.calculationVersion = calculationVersion;
        entity.qualityStatus = "VALID";
        entity.createdAt = Instant.now();
        return entity;
    }

    public void correctProvider(BigDecimal value, UUID rawPayloadId, UUID dataVersionId) {
        this.value = value;
        this.rawPayloadId = rawPayloadId;
        this.dataVersionId = dataVersionId;
        this.qualityStatus = "VALID";
    }

    public void recalculate(BigDecimal value) {
        this.value = value;
        this.qualityStatus = "VALID";
    }
}
