package com.hethongdata.taichinh.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

@Entity
@Table(name = "macro_observations")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class MacroObservationEntity {

    @Id
    @Column(name = "id")
    private Long id;

    @Column(name = "macro_series_id")
    private UUID macroSeriesId;

    @Column(name = "observation_date")
    private LocalDate observationDate;

    @Column(name = "value")
    private BigDecimal value;

    @Column(name = "value_text")
    private String valueText;

    @Column(name = "raw_payload_id")
    private UUID rawPayloadId;

    @Column(name = "data_version_id")
    private UUID dataVersionId;

    @Column(name = "created_at")
    private Instant createdAt;
}
