package com.hethongdata.taichinh.entity.account;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "revoked_access_tokens")
public class RevokedAccessTokenEntity {
    @Id
    @Column(name = "token_id")
    private UUID tokenId;

    @Column(name = "expires_at", nullable = false)
    private Instant expiresAt;

    protected RevokedAccessTokenEntity() {}
}
