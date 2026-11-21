package com.hethongdata.taichinh.dto.auth;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.time.Instant;

public final class AuthDtos {
    private AuthDtos() {}

    public record LoginRequest(@NotBlank @Size(max = 100) String username,
            @NotBlank @Size(max = 72) String password) {
        @Override public String toString() { return "LoginRequest[redacted]"; }
    }

    public record LoginResponse(String accessToken, String tokenType, Instant expiresAt) {
        @Override public String toString() { return "LoginResponse[redacted]"; }
    }
}
