package com.hethongdata.taichinh.controller;

import com.hethongdata.taichinh.dto.auth.AuthDtos.LoginRequest;
import com.hethongdata.taichinh.dto.auth.AuthDtos.LoginResponse;
import com.hethongdata.taichinh.jwt.JwtService;
import com.hethongdata.taichinh.security.AccountSecurityService;
import jakarta.validation.Valid;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.authentication.DisabledException;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

@RestController
@RequestMapping("/api/auth")
public class AuthController {
    private final AccountSecurityService accounts;
    private final JwtService tokens;

    public AuthController(AccountSecurityService accounts, JwtService tokens) {
        this.accounts = accounts;
        this.tokens = tokens;
    }

    @PostMapping("/login")
    public ResponseEntity<LoginResponse> login(@Valid @RequestBody LoginRequest request) {
        try {
            var authentication = accounts.authenticate(request.username().trim(), request.password());
            var token = tokens.issue(authentication, accounts.authenticatedEmail(authentication));
            return ResponseEntity.ok().cacheControl(CacheControl.noStore())
                    .body(new LoginResponse(token.getTokenValue(), "Bearer", token.getExpiresAt()));
        } catch (BadCredentialsException | DisabledException denied) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Invalid username or password");
        }
    }
}
