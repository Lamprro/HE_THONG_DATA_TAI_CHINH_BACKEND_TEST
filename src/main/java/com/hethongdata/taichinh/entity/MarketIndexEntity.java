package com.hethongdata.taichinh.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

import org.hibernate.annotations.UuidGenerator;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "market_indices")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class MarketIndexEntity {

    @Id
    @UuidGenerator
    @Column(name = "id")
    private UUID id;

    @Column(name = "code")
    private String code;

    @Column(name = "name")
    private String name;

    @Column(name = "exchange")
    private String exchange;

    @Column(name = "currency")
    private String currency;

    @Column(name = "description")
    private String description;

    @Column(name = "is_benchmark")
    private Boolean isBenchmark;

    @Column(name = "is_active")
    private Boolean isActive;

    @Column(name = "created_at")
    private Instant createdAt;

    public static MarketIndexEntity create(
            String code, String name, String exchange, String currency, String description,
            boolean benchmark, boolean active) {
        MarketIndexEntity entity = new MarketIndexEntity();
        entity.code = code;
        entity.name = name;
        entity.exchange = exchange;
        entity.currency = currency;
        entity.description = description;
        entity.isBenchmark = benchmark;
        entity.isActive = active;
        entity.createdAt = Instant.now();
        return entity;
    }

    public void update(
            String name, String exchange, String currency, String description,
            boolean benchmark, boolean active) {
        this.name = name;
        this.exchange = exchange;
        this.currency = currency;
        this.description = description;
        this.isBenchmark = benchmark;
        this.isActive = active;
    }
}
