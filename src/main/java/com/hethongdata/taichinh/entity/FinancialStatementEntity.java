package com.hethongdata.taichinh.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.UuidGenerator;

@Entity
@Table(name = "financial_statements")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class FinancialStatementEntity {

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

    @Column(name = "statement_type")
    private String statementType;

    @Column(name = "report_scope")
    private String reportScope;

    @Column(name = "accounting_standard")
    private String accountingStandard;

    @Column(name = "currency")
    private String currency;

    @Column(name = "unit_scale")
    private Long unitScale;

    @Column(name = "audited")
    private Boolean audited;

    @Column(name = "revision_no")
    private Integer revisionNo;

    @Column(name = "is_restated")
    private Boolean isRestated;

    @Column(name = "data_source_id")
    private Long dataSourceId;

    @Column(name = "raw_payload_id")
    private UUID rawPayloadId;

    @Column(name = "data_version_id")
    private UUID dataVersionId;

    @Column(name = "published_at")
    private Instant publishedAt;

    @Column(name = "effective_from")
    private Instant effectiveFrom;

    @Column(name = "effective_to")
    private Instant effectiveTo;

    @Column(name = "is_current")
    private Boolean isCurrent;

    @Column(name = "is_canonical")
    private Boolean isCanonical;

    @Column(name = "created_at")
    private Instant createdAt;

}
