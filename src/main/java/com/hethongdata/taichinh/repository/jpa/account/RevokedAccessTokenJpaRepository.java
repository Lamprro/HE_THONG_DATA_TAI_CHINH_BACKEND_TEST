package com.hethongdata.taichinh.repository.jpa.account;

import com.hethongdata.taichinh.entity.account.RevokedAccessTokenEntity;
import java.time.Instant;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;

public interface RevokedAccessTokenJpaRepository extends JpaRepository<RevokedAccessTokenEntity, UUID> {
    @Modifying
    @Query(value = """
            INSERT INTO revoked_access_tokens (token_id, expires_at)
            VALUES (:tokenId, :expiresAt)
            ON CONFLICT (token_id) DO NOTHING
            """, nativeQuery = true)
    void revoke(UUID tokenId, Instant expiresAt);

    @Modifying
    @Query("DELETE FROM RevokedAccessTokenEntity token WHERE token.expiresAt < :now")
    void deleteExpired(Instant now);
}
