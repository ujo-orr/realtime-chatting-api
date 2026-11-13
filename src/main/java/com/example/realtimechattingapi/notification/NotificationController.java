package com.example.realtimechattingapi.notification;

import com.example.realtimechattingapi.auth.JwtTokenProvider.TokenIdentity;
import com.example.realtimechattingapi.global.CursorPage;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.constraints.*;
import lombok.RequiredArgsConstructor;
import org.springframework.http.*;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

@RestController @RequestMapping("/api/notifications") @RequiredArgsConstructor
public class NotificationController {
    private final NotificationService notifications;
    private final NotificationStreamRegistry streams;

    @GetMapping
    public CursorPage<NotificationService.View> history(@AuthenticationPrincipal TokenIdentity identity,
                                                        @RequestParam(defaultValue = "0") @Min(0) long afterId,
                                                        @RequestParam(defaultValue = "20") @Min(1) @Max(100) int limit) {
        return notifications.history(identity.userId(), afterId, limit);
    }

    @GetMapping(value = "/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter stream(@AuthenticationPrincipal TokenIdentity identity, HttpServletResponse response) {
        response.setHeader(HttpHeaders.CACHE_CONTROL, "no-store");
        return streams.open(identity);
    }
}
