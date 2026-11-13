package com.example.realtimechattingapi.global;

import com.example.realtimechattingapi.auth.*;
import com.example.realtimechattingapi.user.UserRepository;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.*;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import org.springframework.security.web.csrf.*;

@Configuration
public class SecurityConfig {
    @Bean PasswordEncoder passwordEncoder() { return new BCryptPasswordEncoder(); }

    @Bean
    SecurityFilterChain securityFilterChain(HttpSecurity http, AccessTokenValidator validator,
                                            SecurityErrorWriter errors, @Value("${auth.cookie-secure}") boolean secure) throws Exception {
        CookieCsrfTokenRepository csrfRepository = CookieCsrfTokenRepository.withHttpOnlyFalse();
        csrfRepository.setCookieCustomizer(cookie -> cookie.path("/").sameSite("Strict").secure(secure));
        return http.sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .csrf(csrf -> csrf.csrfTokenRepository(csrfRepository))
                .formLogin(form -> form.disable()).httpBasic(basic -> basic.disable())
                .logout(logout -> logout.disable()).requestCache(cache -> cache.disable())
                .authorizeHttpRequests(auth -> auth
                        .requestMatchers(request -> request.getDispatcherType() == jakarta.servlet.DispatcherType.ASYNC
                                && request.getRequestURI().equals("/api/notifications/stream")).permitAll()
                        .requestMatchers(HttpMethod.GET, "/api/auth/csrf", "/ws", "/chat-test.html", "/chat-test.js", "/recovery-client.mjs").permitAll()
                        .requestMatchers(HttpMethod.POST, "/api/auth/signup", "/api/auth/login", "/api/auth/refresh").permitAll()
                        .anyRequest().authenticated())
                .exceptionHandling(ex -> ex
                        .authenticationEntryPoint((req, res, e) -> errors.write(req, res, 401, "UNAUTHORIZED", "인증이 필요합니다."))
                        .accessDeniedHandler((req, res, e) -> errors.write(req, res, 403, "FORBIDDEN", "접근 권한 또는 CSRF 토큰을 확인해 주세요.")))
                .addFilterBefore(new JwtAuthenticationFilter(validator, errors), UsernamePasswordAuthenticationFilter.class)
                .build();
    }
}
