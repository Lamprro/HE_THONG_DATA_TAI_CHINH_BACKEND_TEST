package com.hethongdata.taichinh.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

import org.hibernate.annotations.UuidGenerator;

import java.time.LocalDate;
import java.util.UUID;

@Entity
@Table(name = "financial_periods")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class FinancialPeriodEntity {

    @Id
    @UuidGenerator
    @Column(name = "id")
    private UUID id;

    @Column(name = "fiscal_year")
    private Short fiscalYear;

    @Column(name = "period_type")
    private String periodType;

    @Column(name = "start_date")
    private LocalDate startDate;

    @Column(name = "end_date")
    private LocalDate endDate;

    @Column(name = "report_date")
    private LocalDate reportDate;

    @Column(name = "is_audited_period")
    private Boolean isAuditedPeriod;
}
