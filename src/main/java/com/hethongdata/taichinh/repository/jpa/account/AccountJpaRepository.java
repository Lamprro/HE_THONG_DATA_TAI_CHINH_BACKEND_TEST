package com.hethongdata.taichinh.repository.jpa.account;

import com.hethongdata.taichinh.entity.account.AccountEntity;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;

public interface AccountJpaRepository extends JpaRepository<AccountEntity, UUID> {
    Optional<AccountEntity> findByUserId(UUID userId);

    @EntityGraph(attributePaths = "role")
    List<AccountEntity> findTop2ByUsername(String username);
}
