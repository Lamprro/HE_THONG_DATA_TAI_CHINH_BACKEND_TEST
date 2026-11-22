package com.hethongdata.taichinh.repository.jpa.account;

import com.hethongdata.taichinh.entity.account.AccountEntity;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.transaction.annotation.Transactional;

public interface AccountJpaRepository extends JpaRepository<AccountEntity, UUID> {
    Optional<AccountEntity> findByUserId(UUID userId);

    @EntityGraph(attributePaths = "role")
    List<AccountEntity> findTop2ByUsername(String username);

    @Modifying
    @Transactional
    @Query("""
        update AccountEntity account set account.passwordHash = :replacement
        where account.id = :id and account.passwordHash = :previous and upper(account.status) = 'ACTIVE'
        """)
    int replacePasswordHash(UUID id, String previous, String replacement);
}
