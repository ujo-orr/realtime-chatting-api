package com.example.realtimechattingapi.websocket;

import com.example.realtimechattingapi.chatroom.RoomAccessService;
import lombok.RequiredArgsConstructor;
import org.springframework.messaging.*;
import org.springframework.messaging.simp.*;
import org.springframework.messaging.support.ExecutorChannelInterceptor;

@RequiredArgsConstructor
public class StompOutboundInterceptor implements ExecutorChannelInterceptor {
    private final ConnectionRegistry connections;
    private final RoomAccessService rooms;

    @Override public Message<?> beforeHandle(Message<?> message, MessageChannel channel, MessageHandler handler) {
        if (SimpMessageHeaderAccessor.getMessageType(message.getHeaders()) != SimpMessageType.MESSAGE) return message;
        String id = SimpMessageHeaderAccessor.getSessionId(message.getHeaders());
        try {
            var identity = connections.requireCurrent(id);
            rooms.requireMember(RoomDestination.roomId(SimpMessageHeaderAccessor.getDestination(message.getHeaders()), false), identity.userId());
            return message;
        } catch (RuntimeException ex) {
            connections.close(id, "DELIVERY_DENIED");
            return null;
        }
    }
}
