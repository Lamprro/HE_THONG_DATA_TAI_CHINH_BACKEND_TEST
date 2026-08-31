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

@Entity
@Table(name = "company_aliases")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class CompanyAliasEntity {

    @Id
    @Column(name = "id")
    private Long id;

    @Column(name = "company_id")
    private UUID companyId;

    @Column(name = "alias")
    private String alias;

    @Column(name = "alias_type")
    private String aliasType;

    @Column(name = "created_at")
    private Instant createdAt;

}
