package com.hethongdata.taichinh.entity.account;

import jakarta.persistence.*;
import java.util.UUID;
import lombok.Getter;

@Entity
@Table(name = "accounts")
@Getter
public class AccountEntity {
    @Id
    private UUID id;

    @Column(name = "user_id")
    private UUID userId;

    private String username;
    private String email;
    private String status;

    @Column(name = "password_hash")
    private String passwordHash;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "role_id")
    private RoleEntity role;

    protected AccountEntity() {}
}
