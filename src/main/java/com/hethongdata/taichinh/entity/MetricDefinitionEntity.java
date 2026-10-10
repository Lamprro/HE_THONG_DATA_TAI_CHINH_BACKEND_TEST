package com.hethongdata.taichinh.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.Instant;

@Entity
@Table(name = "metric_definitions")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class MetricDefinitionEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id")
    private Long id;

    @Column(name = "code")
    private String code;

    @Column(name = "name")
    private String name;

    @Column(name = "category")
    private String category;

    @Column(name = "description")
    private String description;

    @Column(name = "formula")
    private String formula;

    @Column(name = "unit")
    private String unit;

    @Column(name = "higher_is_better")
    private Boolean higherIsBetter;

    @Column(name = "created_at")
    private Instant createdAt;

    @Column(name = "updated_at")
    private Instant updatedAt;

    public static MetricDefinitionEntity create(String code, String name, String category,
            String description, String formula, String unit, boolean higherIsBetter) {
        MetricDefinitionEntity entity = new MetricDefinitionEntity();
        entity.code = code;
        entity.name = name;
        entity.category = category;
        entity.description = description;
        entity.formula = formula;
        entity.unit = unit;
        entity.higherIsBetter = higherIsBetter;
        entity.createdAt = Instant.now();
        entity.updatedAt = entity.createdAt;
        return entity;
    }

    public void refresh(String name, String category, String description, String formula,
            String unit, boolean higherIsBetter) {
        this.name = name;
        this.category = category;
        this.description = description;
        this.formula = formula;
        this.unit = unit;
        this.higherIsBetter = higherIsBetter;
        this.updatedAt = Instant.now();
    }
}
