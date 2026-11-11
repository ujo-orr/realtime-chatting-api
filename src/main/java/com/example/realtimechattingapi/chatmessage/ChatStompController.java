package com.example.realtimechattingapi.chatmessage;

import com.example.realtimechattingapi.websocket.*;
import jakarta.validation.Validator;
import jakarta.validation.constraints.*;
import lombok.RequiredArgsConstructor;
import org.springframework.messaging.handler.annotation.*;
import org.springframework.messaging.simp.SimpMessageHeaderAccessor;
import org.springframework.stereotype.Controller;
import org.springframework.security.core.Authentication;
import java.security.Principal;
import tools.jackson.databind.ObjectMapper;

@Controller @RequiredArgsConstructor
public class ChatStompController {
    private final ChatMessageService messages;
    private final ConnectionRegistry connections;
    private final ObjectMapper mapper;
    private final Validator validator;

    @MessageMapping("/rooms/{roomId}/messages")
    public void send(@DestinationVariable Long roomId, @Payload byte[] payload, SimpMessageHeaderAccessor headers,
                     Principal principal) {
        var current = connections.requireCurrent(headers.getSessionId());
        if (!(principal instanceof Authentication authentication)
                || !(authentication.getPrincipal() instanceof SocketPrincipal user)
                || !user.identity().equals(current)) throw new IllegalArgumentException("Authentication required");
        var body = mapper.readTree(payload);
        if (!body.isObject() || body.size() != 1 || !body.has("content") || !body.get("content").isString()) {
            throw new IllegalArgumentException("Only content is accepted");
        }
        var request = mapper.treeToValue(body, SendMessage.class);
        if (!validator.validate(request).isEmpty()) throw new IllegalArgumentException("Invalid message content");
        messages.send(user.identity(), roomId, request.content());
    }

    @MessageExceptionHandler
    public void rejected(Exception ex, SimpMessageHeaderAccessor headers) {
        connections.close(headers.getSessionId(), "INVALID_MESSAGE");
    }

    public record SendMessage(@NotBlank @Size(max = 2000) String content) {}
}
