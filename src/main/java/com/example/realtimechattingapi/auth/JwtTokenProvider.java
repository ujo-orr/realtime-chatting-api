package com.example.realtimechattingapi.auth;

import com.example.realtimechattingapi.global.ApiException;
import io.jsonwebtoken.*;
import io.jsonwebtoken.security.Keys;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.security.*;
import java.time.*;
import java.util.*;

@Component
public class JwtTokenProvider {
    private final SecretKey key;
    private final Duration accessTtl;
    private final Duration refreshTtl;
    private static final String ISSUER = "realtime-chatting-api";

    public JwtTokenProvider(@Value("${jwt.secret}") String secret,
                            @Value("${jwt.access-ttl}") Duration accessTtl,
                            @Value("${jwt.refresh-ttl}") Duration refreshTtl) {
        this.key = Keys.hmacShaKeyFor(secret.getBytes(StandardCharsets.UTF_8));
        if (accessTtl.isNegative() || accessTtl.isZero() || refreshTtl.isNegative() || refreshTtl.isZero()) {
            throw new IllegalArgumentException("Token lifetimes must be positive");
        }
        this.accessTtl = accessTtl;
        this.refreshTtl = refreshTtl;
    }

    public IssuedToken issue(Long userId, long version, String purpose) {
        Instant now = Instant.now();
        Duration ttl = switch (purpose) {
            case "access" -> accessTtl;
            case "refresh" -> refreshTtl;
            default -> throw new IllegalArgumentException("Unknown token purpose");
        };
        Instant expiresAt = now.plus(ttl).truncatedTo(java.time.temporal.ChronoUnit.SECONDS);
        String value = Jwts.builder().issuer(ISSUER).subject(userId.toString())
                .id(UUID.randomUUID().toString()).issuedAt(Date.from(now))
                .expiration(Date.from(expiresAt)).claim("purpose", purpose)
                .claim("tokenVersion", version).signWith(key).compact();
        return new IssuedToken(value, expiresAt);
    }

    public TokenIdentity verify(String token, String purpose) {
        try {
            Claims claims = Jwts.parser().verifyWith(key).requireIssuer(ISSUER)
                    .require("purpose", purpose).build().parseSignedClaims(token).getPayload();
            Long userId = Long.valueOf(claims.getSubject());
            Long version = claims.get("tokenVersion", Long.class);
            if (userId <= 0 || version == null || version < 0 || claims.getExpiration() == null
                    || claims.getIssuedAt() == null || claims.getId() == null) throw ApiException.unauthorized();
            return new TokenIdentity(userId, version, claims.getExpiration().toInstant());
        } catch (JwtException | IllegalArgumentException ex) {
            throw ApiException.unauthorized();
        }
    }

    public static String hash(String token) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(token.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException ex) { throw new IllegalStateException(ex); }
    }

    public record IssuedToken(String value, Instant expiresAt) {}
    public record TokenIdentity(Long userId, long version, Instant expiresAt) {}
}
