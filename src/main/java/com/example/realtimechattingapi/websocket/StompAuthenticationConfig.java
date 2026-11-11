package com.example.realtimechattingapi.websocket;

import com.example.realtimechattingapi.auth.AccessTokenValidator;
import com.example.realtimechattingapi.chatroom.RoomAccessService;
import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.*;
import org.springframework.core.annotation.Order;
import org.springframework.messaging.simp.config.ChannelRegistration;
import org.springframework.web.socket.config.annotation.WebSocketMessageBrokerConfigurer;

@Configuration @RequiredArgsConstructor
@Order(Ordered.HIGHEST_PRECEDENCE + 99)
public class StompAuthenticationConfig implements WebSocketMessageBrokerConfigurer {
    private final AccessTokenValidator validator;
    private final ConnectionRegistry connections;
    private final RoomAccessService rooms;
    @Override public void configureClientInboundChannel(ChannelRegistration registration) {
        registration.interceptors(new StompInboundInterceptor(validator, connections, rooms));
    }
}
