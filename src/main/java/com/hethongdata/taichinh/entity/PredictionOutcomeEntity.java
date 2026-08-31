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
@Table(name = "prediction_outcomes")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class PredictionOutcomeEntity {

    @Id
    @UuidGenerator
    @Column(name = "prediction_id")
    private UUID predictionId;

    @Column(name = "realized_at")
    private LocalDate realizedAt;

    @Column(name = "security_return")
    private BigDecimal securityReturn;

    @Column(name = "benchmark_return")
    private BigDecimal benchmarkReturn;

    @Column(name = "excess_return")
    private BigDecimal excessReturn;

    @Column(name = "actual_label")
    private String actualLabel;

    @Column(name = "is_correct")
    private Boolean isCorrect;

    @Column(name = "evaluated_at")
    private Instant evaluatedAt;

}
