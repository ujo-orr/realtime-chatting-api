package com.example.realtimechattingapi.websocket;

import org.springframework.context.annotation.*;
import org.springframework.messaging.Message;
import org.springframework.messaging.simp.SimpMessageType;
import org.springframework.security.authorization.AuthorizationManager;
import org.springframework.security.config.annotation.web.socket.EnableWebSocketSecurity;
import org.springframework.security.messaging.access.intercept.MessageMatcherDelegatingAuthorizationManager;

@Configuration @EnableWebSocketSecurity
public class StompSecurityConfig {
    @Bean
    AuthorizationManager<Message<?>> messageAuthorizationManager(MessageMatcherDelegatingAuthorizationManager.Builder messages) {
        return messages.simpTypeMatchers(SimpMessageType.DISCONNECT).permitAll()
                .simpTypeMatchers(SimpMessageType.CONNECT, SimpMessageType.HEARTBEAT, SimpMessageType.UNSUBSCRIBE).authenticated()
                .simpDestMatchers("/app/rooms/*/messages").authenticated()
                .simpSubscribeDestMatchers("/topic/rooms/*").authenticated()
                .anyMessage().denyAll().build();
    }
}
