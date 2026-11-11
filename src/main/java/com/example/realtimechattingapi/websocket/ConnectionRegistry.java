package com.example.realtimechattingapi.websocket;

import com.example.realtimechattingapi.auth.*;
import com.example.realtimechattingapi.global.ApiException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.*;
import org.springframework.scheduling.TaskScheduler;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.*;
import org.springframework.web.socket.*;
import java.io.IOException;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;

@Component @Slf4j
public class ConnectionRegistry {
    private final Map<String, Connection> connections = new ConcurrentHashMap<>();
    private final Map<Long, Set<String>> users = new ConcurrentHashMap<>();
    private final TaskScheduler scheduler;
    private final AccessTokenValidator validator;
    private final Duration connectTimeout;

    public ConnectionRegistry(@Qualifier("socketScheduler") TaskScheduler scheduler, AccessTokenValidator validator,
                              @Value("${chat.websocket.connect-timeout:5s}") Duration connectTimeout) {
        this.scheduler = scheduler;
        this.validator = validator;
        this.connectTimeout = connectTimeout;
    }

    public void opened(WebSocketSession socket) {
        Connection connection = new Connection(socket);
        connections.put(socket.getId(), connection);
        synchronized (connection) {
            connection.deadline = scheduler.schedule(() -> close(socket.getId(), "CONNECT_TIMEOUT"), Instant.now().plus(connectTimeout));
        }
        log.info("WS opened connectionId={}", socket.getId());
    }

    public void authenticated(String id, JwtTokenProvider.TokenIdentity identity) {
        Connection connection = connection(id);
        validator.requireCurrent(identity);
        synchronized (connection) {
            if (connection.closed || connection.identity != null) throw ApiException.unauthorized();
            connection.identity = identity;
            if (connection.deadline != null) connection.deadline.cancel(false);
            users.compute(identity.userId(), (userId, ids) -> {
                if (ids == null) ids = ConcurrentHashMap.newKeySet();
                ids.add(id);
                return ids;
            });
            connection.deadline = scheduler.schedule(() -> close(id, "TOKEN_EXPIRED"), identity.expiresAt());
        }
        // 등록과 로그아웃 이벤트 교차 시 현재 버전 재확인
        try { validator.requireCurrent(identity); }
        catch (RuntimeException ex) { close(id, "AUTH_INVALID"); throw ex; }
        log.info("WS authenticated userId={} connectionId={} version={}", identity.userId(), id, identity.version());
    }

    public JwtTokenProvider.TokenIdentity requireCurrent(String id) {
        Connection connection = connection(id);
        var identity = connection.identity;
        try {
            if (connection.closed) throw ApiException.unauthorized();
            validator.requireCurrent(identity);
            return identity;
        } catch (RuntimeException ex) {
            close(id, "AUTH_INVALID");
            throw ex;
        }
    }

    public boolean authenticated(String id) {
        Connection connection = connections.get(id);
        return connection != null && connection.identity != null && !connection.closed;
    }

    public void beforeSocketWrite(String id) {
        Connection connection = connection(id);
        if (connection.closed) throw ApiException.unauthorized();
        if (connection.identity != null) requireCurrent(id);
    }

    public void close(String id, String reason) {
        Connection connection = connections.get(id);
        if (connection == null) return;
        synchronized (connection) {
            if (connection.closed) return;
            connection.closed = true;
        }
        remove(connection);
        try { if (connection.socket.isOpen()) connection.socket.close(new CloseStatus(1008, reason)); }
        catch (IOException ex) { log.debug("WS close failed connectionId={}", id); }
        log.info("WS closed connectionId={} reason={}", id, reason);
    }

    public void closed(String id) {
        Connection connection = connections.get(id);
        if (connection == null) return;
        synchronized (connection) { connection.closed = true; }
        remove(connection);
        log.info("WS disconnected connectionId={}", id);
    }

    private void remove(Connection connection) {
        connections.remove(connection.socket.getId(), connection);
        if (connection.deadline != null) connection.deadline.cancel(false);
        if (connection.identity != null) {
            users.computeIfPresent(connection.identity.userId(), (userId, ids) -> {
                ids.remove(connection.socket.getId());
                return ids.isEmpty() ? null : ids;
            });
        }
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void invalidated(TokensInvalidated event) {
        for (String id : List.copyOf(users.getOrDefault(event.userId(), Set.of()))) {
            Connection connection = connections.get(id);
            if (connection != null && connection.identity != null && connection.identity.version() < event.currentVersion()) {
                close(id, "LOGOUT");
            }
        }
    }

    public int size() { return connections.size(); }
    public int countForUser(Long userId) { return users.getOrDefault(userId, Set.of()).size(); }

    private Connection connection(String id) {
        Connection connection = connections.get(id);
        if (connection == null) throw ApiException.unauthorized();
        return connection;
    }

    private static final class Connection {
        final WebSocketSession socket;
        volatile JwtTokenProvider.TokenIdentity identity;
        volatile ScheduledFuture<?> deadline;
        volatile boolean closed;
        Connection(WebSocketSession socket) { this.socket = socket; }
    }
}
