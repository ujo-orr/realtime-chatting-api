package com.example.realtimechattingapi.auth;

import com.example.realtimechattingapi.global.ApiException;
import com.example.realtimechattingapi.user.*;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Locale;

@Service
@RequiredArgsConstructor
public class AuthService {
    private final UserRepository users;
    private final RefreshTokenRepository refreshTokens;
    private final PasswordEncoder passwords;
    private final JwtTokenProvider jwt;
    private final org.springframework.context.ApplicationEventPublisher events;

    @Transactional
    public AuthDtos.UserView signup(AuthDtos.Signup request) {
        String email = normalize(request.email());
        // BCrypt's limit is bytes, not Java characters.
        if (request.password().getBytes(StandardCharsets.UTF_8).length > 72) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "INVALID_REQUEST", "비밀번호는 UTF-8 기준 72바이트 이하여야 합니다.");
        }
        if (users.existsByEmail(email)) {
            throw new ApiException(HttpStatus.CONFLICT, "EMAIL_EXISTS", "이미 등록된 이메일입니다.");
        }
        User user = users.save(new User(email, passwords.encode(request.password()), request.nickname().strip()));
        return new AuthDtos.UserView(user.getId(), user.getEmail(), user.getNickname());
    }

    @Transactional
    public AuthDtos.TokenPair login(AuthDtos.Login request) {
        User user = users.findLockedByEmail(normalize(request.email())).orElseThrow(ApiException::unauthorized);
        if (request.password().getBytes(StandardCharsets.UTF_8).length > 72
                || !passwords.matches(request.password(), user.getPasswordHash())) throw ApiException.unauthorized();
        // 사용자 행 잠금 아래 최초 Refresh 조회, 없는 행의 gap lock 회피
        return issueAndSave(user, refreshTokens.findByUserId(user.getId()).orElse(null));
    }

    @Transactional
    public AuthDtos.TokenPair refresh(String rawToken) {
        var identity = jwt.verify(rawToken, "refresh");
        User user = users.findLockedById(identity.userId()).orElseThrow(ApiException::unauthorized);
        if (user.getTokenVersion() != identity.version()) throw ApiException.unauthorized();
        RefreshToken stored = refreshTokens.findLockedByUserId(user.getId()).orElseThrow(ApiException::unauthorized);
        if (!stored.getExpiresAt().isAfter(Instant.now())
                || !stored.getTokenHash().equals(JwtTokenProvider.hash(rawToken))) throw ApiException.unauthorized();
        return issueAndSave(user, stored);
    }

    @Transactional
    public void logout(JwtTokenProvider.TokenIdentity identity) {
        User user = users.findLockedById(identity.userId()).orElseThrow(ApiException::unauthorized);
        if (user.getTokenVersion() != identity.version() || !identity.expiresAt().isAfter(Instant.now())) {
            throw ApiException.unauthorized();
        }
        refreshTokens.findLockedByUserId(user.getId()).ifPresent(refreshTokens::delete);
        user.invalidateTokens();
        events.publishEvent(new TokensInvalidated(user.getId(), user.getTokenVersion()));
    }

    private AuthDtos.TokenPair issueAndSave(User user, RefreshToken stored) {
        var access = jwt.issue(user.getId(), user.getTokenVersion(), "access");
        var refresh = jwt.issue(user.getId(), user.getTokenVersion(), "refresh");
        String hash = JwtTokenProvider.hash(refresh.value());
        if (stored == null) refreshTokens.save(new RefreshToken(user.getId(), hash, refresh.expiresAt()));
        else stored.rotate(hash, refresh.expiresAt());
        return new AuthDtos.TokenPair(access, refresh);
    }

    private String normalize(String email) { return email.strip().toLowerCase(Locale.ROOT); }
}
