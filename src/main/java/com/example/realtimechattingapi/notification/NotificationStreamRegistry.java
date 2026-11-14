package com.example.realtimechattingapi.notification;

import com.example.realtimechattingapi.auth.*;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.*;
import org.springframework.scheduling.TaskScheduler;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.*;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;
import java.io.IOException;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;

@Component
@Slf4j
public class NotificationStreamRegistry {
    private final Map<Long, Map<String, Connection>> users = new ConcurrentHashMap<>();
    private final AccessTokenValidator validator;
    private final TaskScheduler scheduler;
    private final Duration timeout;
    private final Duration heartbeat;

    public NotificationStreamRegistry(AccessTokenValidator validator,
                                     @Qualifier("socketScheduler") TaskScheduler scheduler,
                                     @Value("${chat.sse.timeout:30m}") Duration timeout,
                                     @Value("${chat.sse.heartbeat:15s}") Duration heartbeat) {
        this.validator = validator;
        this.scheduler = scheduler;
        this.timeout = timeout;
        this.heartbeat = heartbeat;
    }

    public SseEmitter open(JwtTokenProvider.TokenIdentity identity) {
        validator.requireCurrent(identity);
        var connection = new Connection(identity, new SseEmitter(timeout.toMillis()));
        connection.emitter.onCompletion(() -> remove(connection));
        connection.emitter.onTimeout(() -> close(connection, "TIMEOUT"));
        connection.emitter.onError(error -> remove(connection));
        users.compute(identity.userId(), (id, connections) -> {
            if (connections == null) connections = new ConcurrentHashMap<>();
            connections.put(connection.id, connection);
            return connections;
        });
        synchronized (connection) {
            connection.expiryTask = scheduler.schedule(() -> close(connection, "TOKEN_EXPIRED"), identity.expiresAt());
            connection.heartbeatTask = scheduler.scheduleWithFixedDelay(
                    () -> send(connection, SseEmitter.event().comment("heartbeat")), heartbeat);
        }
        // 등록 후 ready 전의 현재 버전 재확인
        if (!send(connection, SseEmitter.event().name("ready").data(Map.of("connectionId", connection.id)))) {
            validator.requireCurrent(identity);
        }
        log.info("SSE opened userId={} connectionId={} version={}", identity.userId(), connection.id, identity.version());
        return connection.emitter;
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void created(NotificationService.Created event) {
        for (Connection connection : List.copyOf(users.getOrDefault(event.recipientId(), Map.of()).values())) {
            send(connection, SseEmitter.event().id(event.notification().id().toString())
                    .name("notification").data(event.notification()));
        }
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void invalidated(TokensInvalidated event) {
        for (Connection connection : List.copyOf(users.getOrDefault(event.userId(), Map.of()).values())) {
            if (connection.identity.version() < event.currentVersion()) close(connection, "LOGOUT");
        }
    }

    private boolean send(Connection connection, SseEmitter.SseEventBuilder event) {
        synchronized (connection) {
            if (connection.closed) return false;
            try {
                validator.requireCurrent(connection.identity);
                connection.emitter.send(event);
                return true;
            } catch (IOException ex) {
                // 전송 실패의 HTTP 완료는 Servlet 컨테이너에 위임
                remove(connection);
                log.info("SSE disconnected userId={} connectionId={}", connection.identity.userId(), connection.id);
                return false;
            } catch (RuntimeException ex) {
                close(connection, "SEND_FAILED");
                return false;
            }
        }
    }

    private void close(Connection connection, String reason) {
        synchronized (connection) {
            if (connection.closed) return;
            remove(connection);
            connection.emitter.complete();
        }
        log.info("SSE closed userId={} connectionId={} reason={}", connection.identity.userId(), connection.id, reason);
    }

    private void remove(Connection connection) {
        synchronized (connection) {
            if (connection.closed) return;
            connection.closed = true;
            if (connection.expiryTask != null) connection.expiryTask.cancel(false);
            if (connection.heartbeatTask != null) connection.heartbeatTask.cancel(false);
        }
        users.computeIfPresent(connection.identity.userId(), (userId, connections) -> {
            connections.remove(connection.id, connection);
            return connections.isEmpty() ? null : connections;
        });
    }

    public int countForUser(Long userId) { return users.getOrDefault(userId, Map.of()).size(); }
    public int size() { return users.values().stream().mapToInt(Map::size).sum(); }

    private static final class Connection {
        final String id = UUID.randomUUID().toString();
        final JwtTokenProvider.TokenIdentity identity;
        final SseEmitter emitter;
        ScheduledFuture<?> expiryTask;
        ScheduledFuture<?> heartbeatTask;
        boolean closed;
        Connection(JwtTokenProvider.TokenIdentity identity, SseEmitter emitter) { this.identity = identity; this.emitter = emitter; }
    }
}
