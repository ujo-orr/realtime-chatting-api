package com.example.realtimechattingapi.websocket;

import com.example.realtimechattingapi.chatroom.RoomAccessService;
import org.springframework.beans.factory.annotation.*;
import org.springframework.context.annotation.Configuration;
import org.springframework.messaging.*;
import org.springframework.messaging.simp.config.*;
import org.springframework.messaging.simp.stomp.*;
import org.springframework.messaging.support.MessageBuilder;
import org.springframework.scheduling.TaskScheduler;
import org.springframework.web.socket.config.annotation.*;
import org.springframework.web.socket.messaging.StompSubProtocolErrorHandler;
import java.nio.charset.StandardCharsets;

@Configuration
@EnableWebSocketMessageBroker
public class WebSocketConfig implements WebSocketMessageBrokerConfigurer {
    private final ConnectionRegistry connections;
    private final RoomAccessService rooms;
    private final TaskScheduler scheduler;
    private final long heartbeat;
    public WebSocketConfig(ConnectionRegistry connections, RoomAccessService rooms,
                           @Qualifier("socketScheduler") TaskScheduler scheduler,
                           @Value("${chat.websocket.heartbeat-ms:10000}") long heartbeat) {
        this.connections = connections;
        this.rooms = rooms;
        this.scheduler = scheduler;
        this.heartbeat = heartbeat;
    }

    @Override public void registerStompEndpoints(StompEndpointRegistry registry) {
        registry.setErrorHandler(new StompSubProtocolErrorHandler() {
            @Override public Message<byte[]> handleClientMessageProcessingError(Message<byte[]> clientMessage, Throwable ex) {
                var headers = StompHeaderAccessor.create(StompCommand.ERROR);
                headers.setMessage("STOMP request rejected");
                return MessageBuilder.createMessage("{\"code\":\"STOMP_REJECTED\"}".getBytes(StandardCharsets.UTF_8), headers.getMessageHeaders());
            }
        });
        registry.addEndpoint("/ws"); // 기본 동일 출처 검사, SockJS 미사용
    }

    @Override public void configureMessageBroker(MessageBrokerRegistry registry) {
        registry.setApplicationDestinationPrefixes("/app");
        registry.enableSimpleBroker("/topic").setHeartbeatValue(new long[]{heartbeat, heartbeat}).setTaskScheduler(scheduler);
        registry.setPreservePublishOrder(true);
    }

    @Override public void configureClientOutboundChannel(ChannelRegistration registration) {
        registration.interceptors(new StompOutboundInterceptor(connections, rooms));
    }

    @Override public void configureWebSocketTransport(WebSocketTransportRegistration registration) {
        registration.setMessageSizeLimit(16 * 1024).setSendBufferSizeLimit(128 * 1024).setSendTimeLimit(10_000)
                .addDecoratorFactory(handler -> new SocketLifecycleDecorator(handler, connections));
    }
}
