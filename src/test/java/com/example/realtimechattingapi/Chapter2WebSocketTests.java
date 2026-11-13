package com.example.realtimechattingapi;

import com.example.realtimechattingapi.auth.*;
import com.example.realtimechattingapi.chatmessage.*;
import com.example.realtimechattingapi.chatroom.*;
import com.example.realtimechattingapi.user.*;
import com.example.realtimechattingapi.websocket.*;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.messaging.simp.*;
import org.springframework.messaging.simp.broker.SimpleBrokerMessageHandler;
import org.springframework.messaging.support.MessageBuilder;
import org.springframework.test.context.*;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.testcontainers.junit.jupiter.*;
import org.testcontainers.mysql.MySQLContainer;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import tools.jackson.databind.ObjectMapper;

import java.net.URI;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.BooleanSupplier;

import static org.assertj.core.api.Assertions.*;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Testcontainers
class Chapter2WebSocketTests {
    static final String SECRET = "chapter-two-test-only-secret-at-least-32-bytes-long";
    @Container static final MySQLContainer MYSQL = new MySQLContainer("mysql:8.4");
    @DynamicPropertySource static void properties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", MYSQL::getJdbcUrl);
        registry.add("spring.datasource.username", MYSQL::getUsername);
        registry.add("spring.datasource.password", MYSQL::getPassword);
        registry.add("jwt.secret", () -> SECRET);
        registry.add("auth.cookie-secure", () -> false);
        registry.add("chat.websocket.heartbeat-ms", () -> 200);
        registry.add("chat.websocket.connect-timeout", () -> "5s");
    }

    @Autowired JwtTokenProvider jwt;
    @Autowired AccessTokenValidator validator;
    @Autowired UserRepository users;
    @Autowired ChatRoomService rooms;
    @Autowired ChatMessageRepository messages;
    @Autowired AuthService auth;
    @Autowired ConnectionRegistry connections;
    @Autowired JdbcTemplate jdbc;
    @Autowired ObjectMapper json;
    @Autowired SimpleBrokerMessageHandler broker;
    @Autowired ApplicationEventPublisher events;
    @Autowired PlatformTransactionManager transactions;
    @LocalServerPort int port;
    HttpClient http;
    final List<WireClient> sockets = new ArrayList<>();
    User alice, bob, outsider;
    long roomId;

    @BeforeEach void setup() throws Exception {
        http = HttpClient.newHttpClient();
        for (String table : List.of("notifications", "chat_messages", "chat_room_members", "chat_rooms", "refresh_tokens", "users")) jdbc.update("DELETE FROM " + table);
        alice = users.save(new User("alice@example.com", "unused-in-socket-tests", "alice"));
        bob = users.save(new User("bob@example.com", "unused-in-socket-tests", "bob"));
        outsider = users.save(new User("outsider@example.com", "unused-in-socket-tests", "outsider"));
        roomId = rooms.create(alice.getId(), new ChatRoomService.CreateRoom("test", List.of(bob.getId()))).id();
    }

    @AfterEach void cleanup() throws Exception {
        for (var client : sockets) client.abort();
        await(() -> connections.size() == 0, 5000, "all socket registrations removed");
        http.close();
    }

    @Test void twoUsersExchangeCommittedMessagesOverActualWebSockets() throws Exception {
        var a = connected(alice);
        var b = connected(bob);
        subscribe(a, roomId); subscribe(b, roomId);
        awaitSubscriptions(roomId, 2);
        send(a, roomId, "hello Bob");
        var firstA = json.readTree(a.await("MESSAGE", 5000).body);
        var firstB = json.readTree(b.await("MESSAGE", 5000).body);
        assertThat(firstA.get("id").asLong()).isEqualTo(firstB.get("id").asLong());
        assertThat(firstB.get("senderId").asLong()).isEqualTo(alice.getId());
        assertThat(jdbc.queryForObject("SELECT content FROM chat_messages WHERE id=?", String.class, firstB.get("id").asLong())).isEqualTo("hello Bob");
        send(b, roomId, "hello Alice");
        assertThat(json.readTree(a.await("MESSAGE", 5000).body).get("senderId").asLong()).isEqualTo(bob.getId());
        b.await("MESSAGE", 5000);
        assertThat(messages.count()).isEqualTo(2);
    }

    @Test void upgradeIsNotAuthenticationAndConnectTimeoutClosesIdleSocket() throws Exception {
        var client = upgrade(null);
        assertThat(client.socket.isOutputClosed()).isFalse(); // buildAsync 성공 = HTTP 101 완료
        assertThat(connections.countForUser(alice.getId())).isZero();
        assertThat(client.closed.get(8, TimeUnit.SECONDS)).isEqualTo(1008);
    }

    @Test void missingTamperedExpiredAndRefreshTokensCannotConnect() throws Exception {
        for (String token : Arrays.asList(null, "broken.token.value", shortAccess(alice, -10), jwt.issue(alice.getId(), 0, "refresh").value())) {
            var client = upgrade(null);
            client.connect(token, true);
            client.awaitClosed(5000);
            assertThat(client.seenConnected).isFalse();
        }
    }

    @Test void missingAndWrongConnectCsrfAreRejected() throws Exception {
        for (boolean wrong : List.of(false, true)) {
            var client = upgrade(null);
            client.frame("CONNECT", Map.of("accept-version", "1.2", "host", "localhost", "heart-beat", "0,0",
                    "Authorization", "Bearer " + token(alice), wrong ? client.csrfHeader : "unused-header", "wrong"), "");
            client.awaitClosed(5000);
            assertThat(client.seenConnected).isFalse();
        }
    }

    @Test void foreignOriginIsRejectedAtHttpUpgrade() {
        assertThatThrownBy(() -> upgrade("https://foreign.example")).isInstanceOf(ExecutionException.class).hasCauseInstanceOf(WebSocketHandshakeException.class);
    }

    @Test void sendAndSubscribeBeforeConnectAreRejected() throws Exception {
        for (String command : List.of("SEND", "SUBSCRIBE")) {
            var client = upgrade(null);
            client.frame(command, Map.of("destination", command.equals("SEND") ? "/app/rooms/" + roomId + "/messages" : "/topic/rooms/" + roomId, "id", "r"), "{}");
            client.awaitClosed(5000);
            assertThat(client.seenConnected).isFalse();
        }
        assertThat(messages.count()).isZero();
    }

    @Test void nonMemberCannotSubscribeOrSend() throws Exception {
        var subscription = connected(outsider);
        subscribe(subscription, roomId);
        subscription.awaitClosed(5000);
        var sending = connected(outsider);
        send(sending, roomId, "forbidden");
        sending.awaitClosed(5000);
        assertThat(messages.count()).isZero();
    }

    @Test void wildcardAndArbitrarySubscriptionsAreRejected() throws Exception {
        for (String destination : List.of("/topic/rooms/*", "/topic/rooms/**", "/topic/rooms/" + roomId + "/extra", "/queue/private", "/app/rooms/" + roomId + "/messages")) {
            var client = connected(alice);
            client.frame("SUBSCRIBE", Map.of("destination", destination, "id", "r"), "");
            client.awaitClosed(5000);
        }
    }

    @Test void directBrokerAndArbitrarySendsAreRejected() throws Exception {
        for (String destination : List.of("/topic/rooms/" + roomId, "/queue/private", "/app/unknown")) {
            var client = connected(alice);
            client.frame("SEND", Map.of("destination", destination, "content-type", "application/json"), "{\"content\":\"forbidden\"}");
            client.awaitClosed(5000);
        }
        assertThat(messages.count()).isZero();
    }

    @Test void senderSpoofingAndInvalidContentCannotBeStored() throws Exception {
        for (String body : List.of("{\"content\":\"spoof\",\"senderId\":" + bob.getId() + "}", "{\"content\":\"   \"}", "{\"content\":\"" + "a".repeat(2001) + "\"}", "{", "{\"content\":123}")) {
            var client = connected(alice);
            client.frame("SEND", Map.of("destination", "/app/rooms/" + roomId + "/messages", "content-type", "application/json"), body);
            client.awaitClosed(5000);
        }
        assertThat(messages.count()).isZero();
    }

    @Test void normalDisconnectRemovesOnlyItsOwnConnection() throws Exception {
        var first = connected(alice); var second = connected(alice);
        assertThat(connections.countForUser(alice.getId())).isEqualTo(2);
        first.frame("DISCONNECT", Map.of("receipt", "normal"), "");
        first.await("RECEIPT", 5000);
        first.socket.sendClose(1000, "NORMAL").join();
        first.awaitClosed(5000);
        assertThat(first.closed.join()).isEqualTo(1000);
        await(() -> connections.countForUser(alice.getId()) == 1, 5000, "one remaining connection");
        subscribe(second, roomId); awaitSubscriptions(roomId, 1);
        send(second, roomId, "still connected"); second.await("MESSAGE", 5000);
    }

    @Test void abruptTransportAbortRemovesTheConnection() throws Exception {
        var client = connected(alice);
        client.abort();
        await(() -> connections.countForUser(alice.getId()) == 0, 5000, "aborted connection removed");
    }

    @Test void missingNegotiatedHeartbeatsClosesOtherwiseOpenTransport() throws Exception {
        var client = connected(alice);
        long started = System.nanoTime();
        client.stopHeartbeat();
        client.awaitClosed(5000);
        long elapsed = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started);
        assertThat(elapsed).isLessThan(5000);
        System.out.println("HEARTBEAT_MISSING close observed after " + elapsed + " ms (configured 200 ms, tolerance applied by broker)");
        await(() -> connections.countForUser(alice.getId()) == 0, 5000, "heartbeat connection removed");
    }

    @Test void silentSubscriberIsClosedAtAccessExpiryAndReceivesNothingLater() throws Exception {
        String access = shortAccess(alice, 8);
        var shortLived = upgrade(null); shortLived.connect(access, true); shortLived.await("CONNECTED", 5000);
        subscribe(shortLived, roomId); awaitSubscriptions(roomId, 1);
        long started = System.nanoTime();
        shortLived.awaitClosed(12000);
        System.out.println("ACCESS_EXPIRY close observed after " + TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started) + " ms");
        var sender = connected(bob);
        send(sender, roomId, "after expiry");
        await(() -> messages.count() == 1, 5000, "message saved for remaining users");
        assertThat(shortLived.messagesReceived).isZero();
    }

    @Test void logoutClosesAllOldConnectionsButPreservesOtherUser() throws Exception {
        String old = token(alice);
        var first = connected(alice); var second = connected(alice); var other = connected(bob);
        subscribe(first, roomId); subscribe(second, roomId); subscribe(other, roomId); awaitSubscriptions(roomId, 3);
        auth.logout(jwt.verify(old, "access"));
        assertThat(connections.countForUser(alice.getId())).isZero();
        first.awaitClosed(5000); second.awaitClosed(5000);
        assertThat(connections.countForUser(bob.getId())).isEqualTo(1);
        send(other, roomId, "after logout"); other.await("MESSAGE", 5000);
        assertThat(first.messagesReceived + second.messagesReceived).isZero();
        var rejected = upgrade(null); rejected.connect(old, true); rejected.awaitClosed(5000);
        assertThat(rejected.seenConnected).isFalse();
    }

    @Test void refreshDoesNotChangeOldPrincipalAndNewAccessReconnects() throws Exception {
        // Refresh 저장용 정상 로그인 계정
        var account = auth.signup(new AuthDtos.Signup("refresh@example.com", "password123", "refresh"));
        var pair = auth.login(new AuthDtos.Login(account.email(), "password123"));
        String shortToken = shortAccess(users.findById(account.id()).orElseThrow(), 8);
        var old = upgrade(null); old.connect(shortToken, true); old.await("CONNECTED", 5000);
        var rotated = auth.refresh(pair.refresh().value());
        old.awaitClosed(12000);
        var next = upgrade(null); next.connect(rotated.access().value(), true); next.await("CONNECTED", 5000);
        assertThat(connections.countForUser(account.id())).isEqualTo(1);
    }

    @Test void outboundChecksCurrentVersionEvenWithoutLogoutEvent() throws Exception {
        var recipient = connected(alice); var sender = connected(bob);
        subscribe(recipient, roomId); subscribe(sender, roomId); awaitSubscriptions(roomId, 2);
        jdbc.update("UPDATE users SET token_version=token_version+1 WHERE id=?", alice.getId());
        send(sender, roomId, "version changed"); sender.await("MESSAGE", 5000);
        recipient.awaitClosed(5000);
        assertThat(recipient.messagesReceived).isZero();
    }

    @Test void inboundChecksCurrentVersionOnSendAndSubscribe() throws Exception {
        for (String command : List.of("SEND", "SUBSCRIBE")) {
            var client = connected(alice);
            jdbc.update("UPDATE users SET token_version=token_version+1 WHERE id=?", alice.getId());
            if (command.equals("SEND")) send(client, roomId, "revoked"); else subscribe(client, roomId);
            client.awaitClosed(5000);
        }
        assertThat(messages.count()).isZero();
    }

    @Test void rolledBackMessageIsNeitherStoredNorBroadcast() throws Exception {
        var receiver = connected(alice); subscribe(receiver, roomId); awaitSubscriptions(roomId, 1);
        new TransactionTemplate(transactions).executeWithoutResult(status -> {
            var saved = messages.save(new ChatMessage(roomId, alice.getId(), "rollback"));
            events.publishEvent(new ChatMessageEvents.Saved(new ChatMessageService.MessageView(saved.getId(), roomId, alice.getId(), saved.getContent(), saved.getCreatedAt())));
            status.setRollbackOnly();
        });
        assertThat(messages.count()).isZero();
        assertThat(receiver.frames.poll(500, TimeUnit.MILLISECONDS)).isNull();
    }

    @Test void lateLogoutEventDoesNotCloseNewVersionConnection() throws Exception {
        auth.logout(jwt.verify(token(alice), "access"));
        var current = connected(alice);
        new TransactionTemplate(transactions).executeWithoutResult(status -> events.publishEvent(new TokensInvalidated(alice.getId(), 1)));
        assertThat(connections.countForUser(alice.getId())).isEqualTo(1);
        subscribe(current, roomId); awaitSubscriptions(roomId, 1);
        send(current, roomId, "new version survives"); current.await("MESSAGE", 5000);
    }

    @Test void simultaneousFirstLoginsForDifferentUsersSucceed() throws Exception {
        String hash = new org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder().encode("password123");
        jdbc.update("UPDATE users SET password_hash=? WHERE id IN (?,?)", hash, alice.getId(), bob.getId());
        try (var executor = Executors.newFixedThreadPool(2)) {
            for (int attempt = 0; attempt < 6; attempt++) {
                jdbc.update("DELETE FROM refresh_tokens");
                CountDownLatch start = new CountDownLatch(1);
                var first = executor.submit(() -> { start.await(); return auth.login(new AuthDtos.Login(alice.getEmail(), "password123")); });
                var second = executor.submit(() -> { start.await(); return auth.login(new AuthDtos.Login(bob.getEmail(), "password123")); });
                start.countDown();
                assertThat(first.get(15, TimeUnit.SECONDS).access()).isNotNull();
                assertThat(second.get(15, TimeUnit.SECONDS).access()).isNotNull();
                assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM refresh_tokens", Integer.class)).isEqualTo(2);
            }
        }
    }

    private String token(User user) { return jwt.issue(user.getId(), users.findById(user.getId()).orElseThrow().getTokenVersion(), "access").value(); }
    private String shortAccess(User user, int seconds) {
        return Jwts.builder().issuer("realtime-chatting-api").subject(user.getId().toString()).id(UUID.randomUUID().toString())
                .claim("purpose", "access").claim("tokenVersion", user.getTokenVersion())
                .issuedAt(Date.from(Instant.now().minusSeconds(20))).expiration(Date.from(Instant.now().plusSeconds(seconds)))
                .signWith(Keys.hmacShaKeyFor(SECRET.getBytes(StandardCharsets.UTF_8))).compact();
    }
    private WireClient connected(User user) throws Exception {
        String access = token(user);
        var client = upgrade(null); client.connect(access, true); client.await("CONNECTED", 5000); return client;
    }
    private WireClient upgrade(String origin) throws Exception {
        var response = http.send(HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/api/auth/csrf")).GET().build(), HttpResponse.BodyHandlers.ofString());
        assertThat(response.statusCode()).isEqualTo(200);
        var body = json.readTree(response.body());
        var client = new WireClient(body.get("headerName").asText(), body.get("token").asText());
        sockets.add(client);
        client.socket = http.newWebSocketBuilder().header("Cookie", response.headers().firstValue("set-cookie").orElseThrow().split(";", 2)[0])
                .header("Origin", origin == null ? "http://localhost:" + port : origin).subprotocols("v12.stomp")
                .buildAsync(URI.create("ws://localhost:" + port + "/ws"), client).get(5, TimeUnit.SECONDS);
        return client;
    }
    private void subscribe(WireClient client, long room) { client.frame("SUBSCRIBE", Map.of("destination", "/topic/rooms/" + room, "id", "room"), ""); }
    private void send(WireClient client, long room, String content) { client.frame("SEND", Map.of("destination", "/app/rooms/" + room + "/messages", "content-type", "application/json"), json.writeValueAsString(Map.of("content", content))); }
    private void awaitSubscriptions(long room, int count) throws Exception {
        var headers = SimpMessageHeaderAccessor.create(SimpMessageType.MESSAGE); headers.setDestination("/topic/rooms/" + room);
        var probe = MessageBuilder.createMessage(new byte[0], headers.getMessageHeaders());
        await(() -> broker.getSubscriptionRegistry().findSubscriptions(probe).values().stream().mapToInt(List::size).sum() == count, 5000, "broker subscriptions registered");
    }
    private static void await(BooleanSupplier condition, long timeout, String description) throws Exception {
        long end = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeout);
        while (!condition.getAsBoolean() && System.nanoTime() < end) Thread.sleep(20);
        assertThat(condition.getAsBoolean()).as(description).isTrue();
    }

    record Frame(String command, String body) {}
    static final class WireClient implements WebSocket.Listener {
        final BlockingQueue<Frame> frames = new LinkedBlockingQueue<>();
        final CompletableFuture<Integer> closed = new CompletableFuture<>();
        final String csrfHeader, csrfToken;
        final StringBuilder buffer = new StringBuilder();
        volatile WebSocket socket;
        volatile boolean seenConnected;
        volatile int messagesReceived;
        ScheduledExecutorService heartbeat;
        WireClient(String csrfHeader, String csrfToken) { this.csrfHeader = csrfHeader; this.csrfToken = csrfToken; }
        void connect(String token, boolean csrf) {
            Map<String,String> headers = new LinkedHashMap<>(Map.of("accept-version", "1.2", "host", "localhost", "heart-beat", "200,200"));
            if (token != null) headers.put("Authorization", "Bearer " + token);
            if (csrf) headers.put(csrfHeader, csrfToken);
            frame("CONNECT", headers, "");
            heartbeat = Executors.newSingleThreadScheduledExecutor();
            heartbeat.scheduleAtFixedRate(() -> { synchronized (this) { try { if (socket != null && !socket.isOutputClosed()) socket.sendText("\n", true).join(); } catch (RuntimeException ignored) {} } }, 100, 100, TimeUnit.MILLISECONDS);
        }
        synchronized void frame(String command, Map<String,String> headers, String body) {
            StringBuilder text = new StringBuilder(command).append('\n');
            headers.forEach((key,value) -> text.append(key).append(':').append(value).append('\n'));
            if (!body.isEmpty()) text.append("content-length:").append(body.getBytes(StandardCharsets.UTF_8).length).append('\n');
            text.append('\n').append(body).append('\0');
            socket.sendText(text.toString(), true).join();
        }
        Frame await(String command, long timeout) throws Exception {
            long end = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeout);
            while (System.nanoTime() < end) {
                var frame = frames.poll(Math.max(1, TimeUnit.NANOSECONDS.toMillis(end-System.nanoTime())), TimeUnit.MILLISECONDS);
                if (frame != null && frame.command.equals(command)) return frame;
            }
            throw new AssertionError("Missing STOMP " + command + ", closed=" + closed.isDone());
        }
        void awaitClosed(long timeout) throws Exception { closed.get(timeout, TimeUnit.MILLISECONDS); stopHeartbeat(); }
        void stopHeartbeat() { if (heartbeat != null) heartbeat.shutdownNow(); }
        void abort() { stopHeartbeat(); if (socket != null) socket.abort(); }
        @Override public void onOpen(WebSocket socket) { socket.request(1); }
        @Override public CompletionStage<?> onText(WebSocket socket, CharSequence data, boolean last) {
            buffer.append(data);
            while (buffer.length() > 0 && buffer.charAt(0) == '\n') buffer.deleteCharAt(0);
            int end;
            while ((end = buffer.indexOf("\0")) >= 0) {
                String raw = buffer.substring(0, end).stripLeading(); buffer.delete(0, end+1);
                int separator = raw.indexOf("\n\n");
                String command = raw.substring(0, raw.indexOf('\n')).trim();
                if (command.equals("CONNECTED")) seenConnected = true;
                if (command.equals("MESSAGE")) messagesReceived++;
                frames.add(new Frame(command, separator >= 0 ? raw.substring(separator+2) : ""));
                while (buffer.length() > 0 && buffer.charAt(0) == '\n') buffer.deleteCharAt(0);
            }
            socket.request(1); return null;
        }
        @Override public CompletionStage<?> onClose(WebSocket socket, int statusCode, String reason) { closed.complete(statusCode); stopHeartbeat(); return null; }
        @Override public void onError(WebSocket socket, Throwable error) { closed.completeExceptionally(error); stopHeartbeat(); }
    }
}
