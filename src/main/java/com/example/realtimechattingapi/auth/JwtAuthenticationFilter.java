package com.example.realtimechattingapi.auth;

import com.example.realtimechattingapi.global.*;
import com.example.realtimechattingapi.user.UserRepository;
import jakarta.servlet.*;
import jakarta.servlet.http.*;
import lombok.RequiredArgsConstructor;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;
import java.io.IOException;
import java.util.List;

@RequiredArgsConstructor
public class JwtAuthenticationFilter extends OncePerRequestFilter {
    private final AccessTokenValidator validator;
    private final SecurityErrorWriter errors;

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String header = request.getHeader("Authorization");
        if (header != null) {
            try {
                var identity = validator.authenticate(header);
                var context = SecurityContextHolder.createEmptyContext();
                context.setAuthentication(new UsernamePasswordAuthenticationToken(identity, null, List.of()));
                SecurityContextHolder.setContext(context);
            } catch (ApiException ex) {
                SecurityContextHolder.clearContext();
                errors.write(request, response, 401, "UNAUTHORIZED", "인증 정보가 유효하지 않습니다.");
                return;
            } catch (org.springframework.dao.DataAccessException ex) {
                SecurityContextHolder.clearContext();
                errors.write(request, response, 503, "AUTH_UNAVAILABLE", "인증 상태를 확인할 수 없습니다.");
                return;
            }
        }
        chain.doFilter(request, response);
    }
}
