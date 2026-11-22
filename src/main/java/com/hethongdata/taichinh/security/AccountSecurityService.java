package com.hethongdata.taichinh.security;

import com.hethongdata.taichinh.repository.jpa.account.AccountJpaRepository;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.authentication.ProviderManager;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.authentication.dao.DaoAuthenticationProvider;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.userdetails.User;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;

@Service
public class AccountSecurityService {
    private final ProviderManager authentication;

    public AccountSecurityService(AccountJpaRepository accounts, PasswordEncoder passwords) {
        var provider = new DaoAuthenticationProvider(username -> {
            var candidates = accounts.findTop2ByUsername(username);
            if (candidates.size() != 1 || !"ACTIVE".equalsIgnoreCase(candidates.getFirst().getStatus())) {
                throw new UsernameNotFoundException("Account unavailable");
            }
            var account = candidates.getFirst();
            var role = account.getRole().getCode().toUpperCase(Locale.ROOT).replaceFirst("^ROLE_", "");
            if (!List.of("ADMIN", "USER").contains(role)) {
                throw new UsernameNotFoundException("Account role unavailable");
            }
            return new AccountPrincipal(account.getId(), account.getUsername(), account.getEmail(),
                    account.getPasswordHash(), role);
        });
        provider.setPasswordEncoder(passwords);
        provider.setUserDetailsPasswordService((details, hash) -> {
            var principal = (AccountPrincipal) details;
            if (accounts.replacePasswordHash(principal.id, principal.getPassword(), hash) != 1) {
                throw new BadCredentialsException("Account credentials changed");
            }
            return new AccountPrincipal(principal.id, principal.getUsername(), principal.email(), hash, principal.role);
        });
        this.authentication = new ProviderManager(provider);
    }

    public Authentication authenticate(String username, String password) {
        if (password.getBytes(StandardCharsets.UTF_8).length > 72) {
            throw new BadCredentialsException("Invalid credentials");
        }
        return authentication.authenticate(UsernamePasswordAuthenticationToken.unauthenticated(username, password));
    }

    static final class AccountPrincipal extends User {
        private final UUID id;
        private final String email;
        private final String role;

        AccountPrincipal(UUID id, String username, String email, String hash, String role) {
            super(username, hash, true, true, true, true, List.of(new SimpleGrantedAuthority("ROLE_" + role)));
            this.id = id;
            this.email = email;
            this.role = role;
        }

        String email() { return email; }
    }

    public String authenticatedEmail(Authentication authentication) {
        return ((AccountPrincipal) authentication.getPrincipal()).email();
    }
}
