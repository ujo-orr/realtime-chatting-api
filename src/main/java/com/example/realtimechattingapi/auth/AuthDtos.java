package com.example.realtimechattingapi.auth;

import jakarta.validation.constraints.*;
import java.time.Instant;

public final class AuthDtos {
    private AuthDtos() {}
    public record Signup(@NotBlank @Email @Size(max = 254) String email,
                         @NotBlank @Size(min = 8, max = 72) String password,
                         @NotBlank @Size(max = 50) String nickname) {}
    public record Login(@NotBlank @Email @Size(max = 254) String email,
                        @NotBlank @Size(max = 72) String password) {}
    public record UserView(Long id, String email, String nickname) {}
    public record AccessResponse(String accessToken, String tokenType, Instant expiresAt) {}
    public record TokenPair(JwtTokenProvider.IssuedToken access, JwtTokenProvider.IssuedToken refresh) {}
}
