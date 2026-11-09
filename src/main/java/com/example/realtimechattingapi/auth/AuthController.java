package com.example.realtimechattingapi.auth;

import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.*;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.web.csrf.CsrfToken;
import org.springframework.web.bind.annotation.*;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;

@RestController
@RequestMapping("/api/auth")
@RequiredArgsConstructor
public class AuthController {
    public static final String REFRESH_COOKIE = "refresh_token";
    private final AuthService service;
    @Value("${auth.cookie-secure}")
    private boolean secureCookie;

    @GetMapping("/csrf")
    public Map<String, String> csrf(CsrfToken token) {
        return Map.of("headerName", token.getHeaderName(), "token", token.getToken());
    }

    @PostMapping("/signup")
    public ResponseEntity<AuthDtos.UserView> signup(@Valid @RequestBody AuthDtos.Signup request) {
        return ResponseEntity.status(HttpStatus.CREATED).body(service.signup(request));
    }

    @PostMapping("/login")
    public ResponseEntity<AuthDtos.AccessResponse> login(@Valid @RequestBody AuthDtos.Login request) {
        return tokens(service.login(request));
    }

    @PostMapping("/refresh")
    public ResponseEntity<AuthDtos.AccessResponse> refresh(@CookieValue(name = REFRESH_COOKIE, defaultValue = "") String refresh) {
        return tokens(service.refresh(refresh));
    }

    @PostMapping("/logout")
    public ResponseEntity<Void> logout(@AuthenticationPrincipal JwtTokenProvider.TokenIdentity identity) {
        service.logout(identity);
        return ResponseEntity.noContent().header(HttpHeaders.SET_COOKIE, cookie("", Duration.ZERO)).build();
    }

    private ResponseEntity<AuthDtos.AccessResponse> tokens(AuthDtos.TokenPair pair) {
        return ResponseEntity.ok().header(HttpHeaders.SET_COOKIE,
                        cookie(pair.refresh().value(), Duration.between(Instant.now(), pair.refresh().expiresAt())))
                .header(HttpHeaders.CACHE_CONTROL, "no-store")
                .body(new AuthDtos.AccessResponse(pair.access().value(), "Bearer", pair.access().expiresAt()));
    }

    private String cookie(String value, Duration age) {
        return ResponseCookie.from(REFRESH_COOKIE, value).httpOnly(true).secure(secureCookie)
                .sameSite("Strict").path("/api/auth").maxAge(age).build().toString();
    }
}
