package com.example.realtimechattingapi.auth;

import com.example.realtimechattingapi.global.ApiException;
import com.example.realtimechattingapi.user.UserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import java.time.Instant;

@Component
@RequiredArgsConstructor
public class AccessTokenValidator {
    private final JwtTokenProvider jwt;
    private final UserRepository users;

    public JwtTokenProvider.TokenIdentity authenticate(String bearer) {
        if (bearer == null || !bearer.startsWith("Bearer ")) throw ApiException.unauthorized();
        var identity = jwt.verify(bearer.substring(7), "access");
        requireCurrent(identity);
        return identity;
    }

    public void requireCurrent(JwtTokenProvider.TokenIdentity identity) {
        if (identity == null || !identity.expiresAt().isAfter(Instant.now())) throw ApiException.unauthorized();
        var user = users.findById(identity.userId()).orElseThrow(ApiException::unauthorized);
        if (user.getTokenVersion() != identity.version()) throw ApiException.unauthorized();
    }
}
