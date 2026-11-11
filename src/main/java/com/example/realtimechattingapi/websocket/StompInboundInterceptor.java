package com.example.realtimechattingapi.websocket;

import com.example.realtimechattingapi.auth.AccessTokenValidator;
import com.example.realtimechattingapi.chatroom.RoomAccessService;
import lombok.RequiredArgsConstructor;
import org.springframework.messaging.*;
import org.springframework.messaging.simp.stomp.*;
import org.springframework.messaging.support.*;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.access.AccessDeniedException;
import java.util.List;

@RequiredArgsConstructor
public class StompInboundInterceptor implements ChannelInterceptor {
    private final AccessTokenValidator validator;
    private final ConnectionRegistry connections;
    private final RoomAccessService rooms;

    @Override public Message<?> preSend(Message<?> message, MessageChannel channel) {
        var headers = MessageHeaderAccessor.getAccessor(message, StompHeaderAccessor.class);
        if (headers == null) throw new AccessDeniedException("STOMP headers required");
        var command = headers.getCommand();
        if (command == StompCommand.DISCONNECT) return message;
        if (command == StompCommand.CONNECT) {
            var identity = validator.authenticate(headers.getFirstNativeHeader("Authorization"));
            connections.authenticated(headers.getSessionId(), identity);
            headers.setUser(new UsernamePasswordAuthenticationToken(new SocketPrincipal(identity), null, List.of()));
        } else {
            var identity = connections.requireCurrent(headers.getSessionId());
            if (command == StompCommand.SEND || command == StompCommand.SUBSCRIBE) {
                long roomId = RoomDestination.roomId(headers.getDestination(), command == StompCommand.SEND);
                rooms.requireMember(roomId, identity.userId());
            } else if (command != StompCommand.UNSUBSCRIBE && headers.getMessageType() != org.springframework.messaging.simp.SimpMessageType.HEARTBEAT) {
                throw new AccessDeniedException("STOMP command denied");
            }
        }
        return message;
    }
}
