package com.hethongdata.taichinh.entity.account;

import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Getter;

@Entity
@Table(name = "roles")
@Getter
public class RoleEntity {
    @Id
    private Long id;

    private String code;

    protected RoleEntity() {}
}
