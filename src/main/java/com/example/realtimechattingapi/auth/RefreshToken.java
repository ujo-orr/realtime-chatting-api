package com.example.realtimechattingapi.auth;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import java.time.Instant;

@Entity
@Table(name = "refresh_tokens")
@Getter
@NoArgsConstructor(access = lombok.AccessLevel.PROTECTED)
public class RefreshToken {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, unique = true)
    private Long userId;

    @Column(nullable = false, unique = true, length = 64)
    private String tokenHash;

    @Column(nullable = false)
    private Instant expiresAt;

    public RefreshToken(Long userId, String tokenHash, Instant expiresAt) {
        this.userId = userId;
        rotate(tokenHash, expiresAt);
    }
    public void rotate(String tokenHash, Instant expiresAt) {
        this.tokenHash = tokenHash;
        this.expiresAt = expiresAt;
    }
}
