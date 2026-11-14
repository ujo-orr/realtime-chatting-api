package com.example.realtimechattingapi.chatmessage;

import com.example.realtimechattingapi.auth.JwtTokenProvider.TokenIdentity;
import com.example.realtimechattingapi.global.CursorPage;
import jakarta.validation.constraints.*;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/rooms/{roomId}/messages")
@RequiredArgsConstructor
public class ChatMessageController {
    private final ChatMessageService service;

    @GetMapping
    public CursorPage<ChatMessageService.MessageView> history(
            @AuthenticationPrincipal TokenIdentity identity,
            @PathVariable @Positive Long roomId,
            @RequestParam(required = false) @Positive Long beforeId,
            @RequestParam(required = false) @Min(0) Long afterId,
            @RequestParam(defaultValue = "20") @Min(1) @Max(100) int limit) {
        return service.history(identity.userId(), roomId, beforeId, afterId, limit);
    }
}
