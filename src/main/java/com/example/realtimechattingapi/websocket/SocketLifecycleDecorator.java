package com.example.realtimechattingapi.websocket;

import org.springframework.web.socket.*;
import org.springframework.web.socket.handler.*;
import java.io.IOException;

public class SocketLifecycleDecorator extends WebSocketHandlerDecorator {
    private final ConnectionRegistry connections;
    public SocketLifecycleDecorator(WebSocketHandler delegate, ConnectionRegistry connections) {
        super(delegate);
        this.connections = connections;
    }

    @Override public void afterConnectionEstablished(WebSocketSession session) throws Exception {
        connections.opened(session);
        super.afterConnectionEstablished(new WebSocketSessionDecorator(session) {
            @Override public void sendMessage(WebSocketMessage<?> message) throws IOException {
                // 전송 대기열 통과 후 실제 소켓 전송 직전의 만료·버전 검사
                try { connections.beforeSocketWrite(getId()); }
                catch (RuntimeException ex) { throw new IOException("Socket authentication expired"); }
                super.sendMessage(message);
            }
        });
    }

    @Override public void afterConnectionClosed(WebSocketSession session, CloseStatus status) throws Exception {
        try { super.afterConnectionClosed(session, status); }
        finally { connections.closed(session.getId()); }
    }

    @Override public void handleTransportError(WebSocketSession session, Throwable exception) throws Exception {
        connections.close(session.getId(), "TRANSPORT_ERROR");
        super.handleTransportError(session, exception);
    }
}
