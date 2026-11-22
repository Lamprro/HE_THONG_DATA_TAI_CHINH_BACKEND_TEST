package com.hethongdata.taichinh.security;

import com.hethongdata.taichinh.entity.account.AccountEntity;
import com.hethongdata.taichinh.entity.account.RoleEntity;
import com.hethongdata.taichinh.repository.jpa.account.AccountJpaRepository;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.*;

class AccountPasswordEncoderTests {
    private static final String SECRET = "fake account fixture authentication secret";
    private static final String PASSWORD = "test-password";

    @Test
    void hashesWithTheConfiguredSecretAndRefusesWrongSecrets() {
        var passwords = new AccountPasswordEncoder(SECRET);
        String hash = passwords.encode(PASSWORD);
        assertThat(hash).startsWith("{bcrypt-pepper}$2").doesNotContain(PASSWORD, SECRET);
        assertThat(passwords.matches(PASSWORD, hash)).isTrue();
        assertThat(passwords.matches("wrong", hash)).isFalse();
        assertThat(new AccountPasswordEncoder("a different test-only signing key").matches(PASSWORD, hash)).isFalse();
        assertThat(new AccountPasswordEncoder("").matches(PASSWORD, hash)).isFalse();
    }

    @Test
    void correctCredentialsUpgradeTheExistingRowOnlyOnce() {
        var repository = mock(AccountJpaRepository.class);
        var account = mock(AccountEntity.class);
        var role = mock(RoleEntity.class);
        var id = UUID.randomUUID();
        String oldHash = "{bcrypt}" + new BCryptPasswordEncoder().encode(PASSWORD);
        var stored = new AtomicReference<>(oldHash);
        when(repository.findTop2ByUsername("fixture-user")).thenReturn(List.of(account));
        when(account.getId()).thenReturn(id);
        when(account.getUsername()).thenReturn("fixture-user");
        when(account.getStatus()).thenReturn("ACTIVE");
        when(account.getRole()).thenReturn(role);
        when(role.getCode()).thenReturn("USER");
        when(account.getPasswordHash()).thenAnswer(call -> stored.get());
        when(repository.replacePasswordHash(eq(id), eq(oldHash), anyString())).thenAnswer(call -> {
            return stored.compareAndSet(call.getArgument(1), call.getArgument(2)) ? 1 : 0;
        });
        var service = new AccountSecurityService(repository, new AccountPasswordEncoder(SECRET));

        assertThatThrownBy(() -> service.authenticate("fixture-user", "wrong"))
                .isInstanceOf(BadCredentialsException.class);
        verify(repository, never()).replacePasswordHash(any(), anyString(), anyString());
        assertThat(service.authenticate("fixture-user", PASSWORD).getName()).isEqualTo("fixture-user");
        assertThat(stored.get()).startsWith("{bcrypt-pepper}$2");
        assertThat(service.authenticate("fixture-user", PASSWORD).isAuthenticated()).isTrue();
        verify(repository, times(1)).replacePasswordHash(eq(id), eq(oldHash), anyString());
    }
}
