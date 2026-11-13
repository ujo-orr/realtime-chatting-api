package com.example.realtimechattingapi;

import com.example.realtimechattingapi.auth.*;
import com.example.realtimechattingapi.chatmessage.*;
import com.example.realtimechattingapi.chatroom.*;
import com.example.realtimechattingapi.notification.*;
import com.example.realtimechattingapi.user.*;
import com.example.realtimechattingapi.websocket.ConnectionRegistry;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
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
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.junit.jupiter.*;
import org.testcontainers.mysql.MySQLContainer;
import tools.jackson.databind.ObjectMapper;
import java.io.*;
import java.net.URI;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BooleanSupplier;
import static org.assertj.core.api.Assertions.*;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Testcontainers
class Chapter3RecoveryTests {
    static final String SECRET = "chapter-three-test-only-secret-at-least-32-bytes-long";
    @Container static final MySQLContainer MYSQL = new MySQLContainer("mysql:8.4");
    @DynamicPropertySource static void properties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", MYSQL::getJdbcUrl);
        registry.add("spring.datasource.username", MYSQL::getUsername);
        registry.add("spring.datasource.password", MYSQL::getPassword);
        registry.add("jwt.secret", () -> SECRET);
        registry.add("auth.cookie-secure", () -> false);
        registry.add("chat.sse.timeout", () -> "15s");
        registry.add("chat.sse.heartbeat", () -> "100ms");
        registry.add("chat.websocket.heartbeat-ms", () -> 200);
    }
    @Autowired JdbcTemplate jdbc;
    @Autowired ObjectMapper json;
    @Autowired UserRepository users;
    @Autowired JwtTokenProvider jwt;
    @Autowired AuthService auth;
    @Autowired ChatRoomService rooms;
    @Autowired ChatMessageService messages;
    @Autowired NotificationService notifications;
    @Autowired NotificationStreamRegistry streams;
    @Autowired ConnectionRegistry sockets;
    @Autowired SimpleBrokerMessageHandler broker;
    @Autowired ApplicationEventPublisher events;
    @Autowired PlatformTransactionManager transactions;
    @LocalServerPort int port;
    HttpClient http;
    User alice, bob, outsider;
    final List<SseWire> liveStreams = new ArrayList<>();
    final List<Chapter2WebSocketTests.WireClient> liveSockets = new ArrayList<>();

    @BeforeEach void setup() {
        http = HttpClient.newHttpClient();
        for (String table : List.of("notifications","chat_messages","chat_room_members","chat_rooms","refresh_tokens","users")) jdbc.update("DELETE FROM " + table);
        alice = users.save(new User("alice@example.com","unused","alice"));
        bob = users.save(new User("bob@example.com","unused","bob"));
        outsider = users.save(new User("outsider@example.com","unused","outsider"));
    }
    @AfterEach void cleanup() throws Exception {
        for (var stream : liveStreams) stream.close();
        for (var socket : liveSockets) socket.abort();
        await(() -> streams.size() == 0 && sockets.size() == 0,7000,"stream and socket cleanup");
        http.close();
    }

    @Test void mysqlAppliesV2AndStoresOnlyDistinctInvitees() {
        long room = invite(alice,bob.getId(),bob.getId(),alice.getId());
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM flyway_schema_history WHERE version='2' AND success=1",Integer.class)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM notifications WHERE room_id=?",Integer.class,room)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT recipient_id FROM notifications WHERE room_id=?",Long.class,room)).isEqualTo(bob.getId());
        assertThat(jdbc.queryForObject("SELECT type FROM notifications WHERE room_id=?",String.class,room)).isEqualTo("ROOM_INVITED");
    }
    @Test void readyAndCommittedNotificationArriveOnlyForRecipient() throws Exception {
        var recipient = stream(token(bob));
        var other = stream(token(outsider));
        long room = invite(alice,bob.getId());
        var event = recipient.await("notification",5000);
        var body = json.readTree(event.data);
        assertThat(body.get("roomId").asLong()).isEqualTo(room);
        assertThat(Long.parseLong(event.id)).isEqualTo(body.get("id").asLong());
        assertThat(jdbc.queryForObject("SELECT recipient_id FROM notifications WHERE id=?",Long.class,body.get("id").asLong())).isEqualTo(bob.getId());
        assertThat(other.events.poll(400,TimeUnit.MILLISECONDS)).isNull();
        assertThat(recipient.contentType).startsWith("text/event-stream");
    }
    @Test void notificationHistoryIsOwnAscendingExclusiveAndPaged() throws Exception {
        invite(alice,bob.getId()); invite(alice,outsider.getId()); invite(alice,bob.getId()); invite(alice,bob.getId());
        var first = get("/api/notifications?afterId=0&limit=2",token(bob));
        assertThat(first.statusCode()).isEqualTo(200);
        var page = json.readTree(first.body());
        assertThat(page.get("items").size()).isEqualTo(2);
        long cursor = page.get("nextCursor").asLong();
        assertThat(page.get("hasMore").asBoolean()).isTrue();
        var next = json.readTree(get("/api/notifications?afterId=" + cursor + "&limit=2",token(bob)).body());
        assertThat(next.get("items").size()).isEqualTo(1);
        assertThat(next.get("items").get(0).get("id").asLong()).isGreaterThan(cursor);
        assertThat(next.get("hasMore").asBoolean()).isFalse();
        assertThat(json.readTree(get("/api/notifications?recipientId=" + bob.getId(),token(alice)).body()).get("items").size()).isZero();
    }
    @Test void failedRoomCreationAndRollbackDoNotStoreOrDeliverInvites() throws Exception {
        var recipient = stream(token(bob));
        assertThatThrownBy(() -> invite(alice,999999L)).isInstanceOf(com.example.realtimechattingapi.global.ApiException.class);
        new TransactionTemplate(transactions).executeWithoutResult(status -> { invite(alice,bob.getId()); status.setRollbackOnly(); });
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM notifications",Integer.class)).isZero();
        assertThat(recipient.events.poll(400,TimeUnit.MILLISECONDS)).isNull();
    }
    @Test void offlineInvitesAreRecoveredAfterReadyWithoutAutomaticReplay() throws Exception {
        var first = stream(token(bob));
        invite(alice,bob.getId());
        long last = Long.parseLong(first.await("notification",5000).id);
        first.close(); await(() -> streams.countForUser(bob.getId()) == 0,5000,"first SSE removed");
        invite(alice,bob.getId()); invite(alice,bob.getId());
        var next = stream(token(bob));
        var page = json.readTree(get("/api/notifications?afterId=" + last,token(bob)).body());
        assertThat(page.get("items").size()).isEqualTo(2);
        assertThat(next.events.poll(300,TimeUnit.MILLISECONDS)).isNull();
        invite(alice,bob.getId());
        long overlap = Long.parseLong(next.await("notification",5000).id);
        var withLive = json.readTree(get("/api/notifications?afterId=" + last,token(bob)).body()).get("items");
        assertThat(withLive.size()).isEqualTo(3);
        assertThat(withLive.get(2).get("id").asLong()).isEqualTo(overlap);
    }
    @Test void oneSseDisconnectDoesNotRemoveOtherConnectionAndHeartbeatsAreSent() throws Exception {
        var first = stream(token(bob)); var second = stream(token(bob));
        assertThat(streams.countForUser(bob.getId())).isEqualTo(2);
        await(() -> second.heartbeats.get() > 0,3000,"heartbeat comment received");
        first.close(); await(() -> streams.countForUser(bob.getId()) == 1,5000,"only first emitter removed");
        invite(alice,bob.getId()); second.await("notification",5000);
    }
    @Test void emitterTimeoutEndsHttpResponseAndRemovesRegistration() throws Exception {
        var connection = stream(token(bob));
        long started = System.nanoTime();
        connection.ended.get(20,TimeUnit.SECONDS);
        await(() -> streams.countForUser(bob.getId()) == 0,3000,"timeout emitter removed");
        System.out.println("SSE_TIMEOUT observed after " + TimeUnit.NANOSECONDS.toMillis(System.nanoTime()-started) + " ms (15s configured)");
    }
    @Test void silentSseExpiresAndOldAccessCannotReconnect() throws Exception {
        String access = shortToken(bob,8);
        Instant expires = jwt.verify(access,"access").expiresAt();
        var connection = stream(access);
        long started = System.nanoTime();
        connection.ended.get(12,TimeUnit.SECONDS);
        assertThat(Instant.now()).isAfterOrEqualTo(expires);
        assertThat(streams.countForUser(bob.getId())).isZero();
        assertThat(get("/api/notifications/stream",access).statusCode()).isEqualTo(401);
        System.out.println("SSE_EXPIRY observed after " + TimeUnit.NANOSECONDS.toMillis(System.nanoTime()-started) + " ms");
    }
    @Test void logoutEndsAllSseAndWsAndRejectsOldReconnects() throws Exception {
        String access = token(bob);
        var first = stream(access); var second = stream(access); var socket = socket(access);
        auth.logout(jwt.verify(access,"access"));
        assertThat(streams.countForUser(bob.getId())).isZero();
        first.ended.get(5,TimeUnit.SECONDS); second.ended.get(5,TimeUnit.SECONDS); socket.awaitClosed(5000);
        assertThat(get("/api/notifications",access).statusCode()).isEqualTo(401);
        assertThat(get("/api/notifications/stream",access).statusCode()).isEqualTo(401);
        var rejected = rawSocket(); rejected.connect(access,true); rejected.awaitClosed(5000);
        assertThat(rejected.seenConnected).isFalse();
    }
    @Test void dbVersionChangeWithoutLogoutEventStopsSseDelivery() throws Exception {
        var connection = stream(token(bob));
        jdbc.update("UPDATE users SET token_version=token_version+1 WHERE id=?",bob.getId());
        invite(alice,bob.getId());
        connection.ended.get(5,TimeUnit.SECONDS);
        assertThat(connection.events.poll(200,TimeUnit.MILLISECONDS)).isNull();
    }
    @Test void refreshKeepsOldStreamExpiryAndNewAccessOpensANewStream() throws Exception {
        var user = auth.signup(new AuthDtos.Signup("refresh@example.com","password123","refresh"));
        var pair = auth.login(new AuthDtos.Login(user.email(),"password123"));
        var old = stream(shortToken(users.findById(user.id()).orElseThrow(),8));
        var next = auth.refresh(pair.refresh().value());
        old.ended.get(12,TimeUnit.SECONDS);
        var current = stream(next.access().value());
        assertThat(streams.countForUser(user.id())).isEqualTo(1);
        invite(alice,user.id()); current.await("notification",5000);
    }
    @Test void lateLogoutEventPreservesCurrentVersionSse() throws Exception {
        auth.logout(jwt.verify(token(bob),"access"));
        var current = stream(token(bob));
        new TransactionTemplate(transactions).executeWithoutResult(status -> events.publishEvent(new TokensInvalidated(bob.getId(),1)));
        assertThat(streams.countForUser(bob.getId())).isEqualTo(1);
        invite(alice,bob.getId()); current.await("notification",5000);
    }
    @Test void messageAfterCursorIsAscendingPagedAndStillRequiresMembership() throws Exception {
        long room = invite(alice,bob.getId());
        for (int index=0;index<3;index++) messages.send(jwt.verify(token(alice),"access"),room,"message " + index);
        var page = json.readTree(get("/api/rooms/" + room + "/messages?afterId=0&limit=2",token(bob)).body());
        assertThat(page.get("items").size()).isEqualTo(2);
        long first = page.get("items").get(0).get("id").asLong(), second = page.get("items").get(1).get("id").asLong();
        assertThat(second).isGreaterThan(first);
        assertThat(page.get("nextCursor").asLong()).isEqualTo(second);
        assertThat(page.get("hasMore").asBoolean()).isTrue();
        var next = json.readTree(get("/api/rooms/" + room + "/messages?afterId=" + second,token(bob)).body());
        assertThat(next.get("items").size()).isEqualTo(1);
        assertThat(get("/api/rooms/" + room + "/messages?afterId=0",token(outsider)).statusCode()).isEqualTo(403);
    }
    @Test void wsReconnectRecoversCommittedOfflineMessagesAndOverlapsWithLiveHistory() throws Exception {
        long room = invite(alice,bob.getId());
        var first = socket(token(bob)); subscribe(first,room); awaitSubscription(room);
        messages.send(jwt.verify(token(alice),"access"),room,"first");
        long last = json.readTree(first.await("MESSAGE",5000).body()).get("id").asLong();
        first.abort(); await(() -> sockets.countForUser(bob.getId()) == 0,5000,"old WS removed");
        messages.send(jwt.verify(token(alice),"access"),room,"offline one"); messages.send(jwt.verify(token(alice),"access"),room,"offline two");
        var next = socket(token(bob)); subscribe(next,room); awaitSubscription(room);
        messages.send(jwt.verify(token(alice),"access"),room,"live and history");
        long live = json.readTree(next.await("MESSAGE",5000).body()).get("id").asLong();
        var history = json.readTree(get("/api/rooms/" + room + "/messages?afterId=" + last,token(bob)).body()).get("items");
        assertThat(history.size()).isEqualTo(3);
        assertThat(history.get(0).get("content").asText()).isEqualTo("offline one");
        assertThat(history.get(1).get("content").asText()).isEqualTo("offline two");
        assertThat(history.get(2).get("id").asLong()).isEqualTo(live);
    }
    @Test void invalidCursorsAndLimitsReturn400() throws Exception {
        long room = invite(alice,bob.getId());
        for (String query : List.of("beforeId=1&afterId=0","afterId=-1","afterId=bad","afterId=0&limit=101")) {
            assertThat(get("/api/rooms/" + room + "/messages?" + query,token(bob)).statusCode()).isEqualTo(400);
        }
        for (String query : List.of("afterId=-1","limit=0","limit=101")) assertThat(get("/api/notifications?" + query,token(bob)).statusCode()).isEqualTo(400);
    }
    @Test void notificationEndpointsRequireAccessAndRejectRefreshAsBearer() throws Exception {
        for (String path : List.of("/api/notifications","/api/notifications/stream")) {
            assertThat(get(path,null).statusCode()).isEqualTo(401);
            assertThat(get(path,jwt.issue(bob.getId(),0,"refresh").value()).statusCode()).isEqualTo(401);
        }
    }

    @Test void freshCsrfWorksForCookieClientAfterAuthenticatedSseRequest() throws Exception {
        var account=auth.signup(new AuthDtos.Signup("cookie@example.com","password123","cookie"));
        var cookieJar=new java.net.CookieManager(null,java.net.CookiePolicy.ACCEPT_ALL);
        try (var client=HttpClient.newBuilder().cookieHandler(cookieJar).build()) {
            String base="http://localhost:"+port;
            var initial=client.send(HttpRequest.newBuilder(URI.create(base+"/api/auth/csrf")).GET().build(),HttpResponse.BodyHandlers.ofString());
            var csrf=json.readTree(initial.body());
            var login=client.send(HttpRequest.newBuilder(URI.create(base+"/api/auth/login"))
                    .header(csrf.get("headerName").asText(),csrf.get("token").asText()).header("Content-Type","application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(json.writeValueAsString(Map.of("email",account.email(),"password","password123")))).build(),HttpResponse.BodyHandlers.ofString());
            assertThat(login.statusCode()).isEqualTo(200);
            String access=json.readTree(login.body()).get("accessToken").asText();
            var response=client.send(HttpRequest.newBuilder(URI.create(base+"/api/notifications/stream"))
                    .header("Authorization","Bearer "+access).GET().build(),HttpResponse.BodyHandlers.ofInputStream());
            var live=new SseWire(response.body(),response.headers().firstValue("content-type").orElse("")); liveStreams.add(live);
            live.await("ready",5000);
            var fresh=client.send(HttpRequest.newBuilder(URI.create(base+"/api/auth/csrf")).GET().build(),HttpResponse.BodyHandlers.ofString());
            var freshCsrf=json.readTree(fresh.body());
            var created=client.send(HttpRequest.newBuilder(URI.create(base+"/api/rooms"))
                    .header("Authorization","Bearer "+access).header("Content-Type","application/json")
                    .header(freshCsrf.get("headerName").asText(),freshCsrf.get("token").asText())
                    .POST(HttpRequest.BodyPublishers.ofString("{\"name\":\"cookie lifecycle\",\"participantIds\":[]}")).build(),HttpResponse.BodyHandlers.ofString());
            assertThat(created.statusCode()).isEqualTo(201);
            var freshAgain=client.send(HttpRequest.newBuilder(URI.create(base+"/api/auth/csrf")).GET().build(),HttpResponse.BodyHandlers.ofString());
            var csrfAgain=json.readTree(freshAgain.body());
            var loggedOut=client.send(HttpRequest.newBuilder(URI.create(base+"/api/auth/logout"))
                    .header("Authorization","Bearer "+access).header(csrfAgain.get("headerName").asText(),csrfAgain.get("token").asText())
                    .POST(HttpRequest.BodyPublishers.noBody()).build(),HttpResponse.BodyHandlers.ofString());
            assertThat(loggedOut.statusCode()).isEqualTo(204);
            live.ended.get(5,TimeUnit.SECONDS);
        }
    }

    long invite(User creator,Long... ids) { return rooms.create(creator.getId(),new ChatRoomService.CreateRoom("invite",Arrays.asList(ids))).id(); }
    String token(User user) { return jwt.issue(user.getId(),users.findById(user.getId()).orElseThrow().getTokenVersion(),"access").value(); }
    String shortToken(User user,int seconds) {
        return Jwts.builder().issuer("realtime-chatting-api").subject(user.getId().toString()).id(UUID.randomUUID().toString())
                .claim("purpose","access").claim("tokenVersion",user.getTokenVersion())
                .issuedAt(Date.from(Instant.now())).expiration(Date.from(Instant.now().plusSeconds(seconds)))
                .signWith(Keys.hmacShaKeyFor(SECRET.getBytes(StandardCharsets.UTF_8))).compact();
    }
    HttpResponse<String> get(String path,String token) throws Exception {
        var builder=HttpRequest.newBuilder(URI.create("http://localhost:"+port+path)).GET();
        if (token!=null) builder.header("Authorization","Bearer "+token);
        return http.send(builder.build(),HttpResponse.BodyHandlers.ofString());
    }
    SseWire stream(String access) throws Exception {
        var response=http.send(HttpRequest.newBuilder(URI.create("http://localhost:"+port+"/api/notifications/stream"))
                .header("Authorization","Bearer "+access).GET().build(),HttpResponse.BodyHandlers.ofInputStream());
        assertThat(response.statusCode()).isEqualTo(200);
        var stream=new SseWire(response.body(),response.headers().firstValue("content-type").orElse(""));
        liveStreams.add(stream); stream.await("ready",5000); return stream;
    }
    Chapter2WebSocketTests.WireClient socket(String access) throws Exception { var client=rawSocket(); client.connect(access,true); client.await("CONNECTED",5000); return client; }
    Chapter2WebSocketTests.WireClient rawSocket() throws Exception {
        var response=get("/api/auth/csrf",null); var body=json.readTree(response.body());
        var client=new Chapter2WebSocketTests.WireClient(body.get("headerName").asText(),body.get("token").asText()); liveSockets.add(client);
        client.socket=http.newWebSocketBuilder().header("Cookie",response.headers().firstValue("set-cookie").orElseThrow().split(";",2)[0])
                .header("Origin","http://localhost:"+port).subprotocols("v12.stomp")
                .buildAsync(URI.create("ws://localhost:"+port+"/ws"),client).get(5,TimeUnit.SECONDS); return client;
    }
    void subscribe(Chapter2WebSocketTests.WireClient client,long room) { client.frame("SUBSCRIBE",Map.of("id","room","destination","/topic/rooms/"+room),""); }
    void awaitSubscription(long room) throws Exception {
        var headers=SimpMessageHeaderAccessor.create(SimpMessageType.MESSAGE); headers.setDestination("/topic/rooms/"+room);
        var probe=MessageBuilder.createMessage(new byte[0],headers.getMessageHeaders());
        await(() -> !broker.getSubscriptionRegistry().findSubscriptions(probe).isEmpty(),5000,"broker subscription ready");
    }
    static void await(BooleanSupplier condition,long timeout,String reason) throws Exception {
        long end=System.nanoTime()+TimeUnit.MILLISECONDS.toNanos(timeout);
        while (!condition.getAsBoolean() && System.nanoTime()<end) Thread.sleep(20);
        assertThat(condition.getAsBoolean()).as(reason).isTrue();
    }
    record SseFrame(String event,String id,String data) {}
    static class SseWire implements AutoCloseable {
        final InputStream input; final String contentType;
        final BlockingQueue<SseFrame> events=new LinkedBlockingQueue<>();
        final CompletableFuture<Void> ended=new CompletableFuture<>();
        final AtomicInteger heartbeats=new AtomicInteger();
        SseWire(InputStream input,String contentType) {
            this.input=input; this.contentType=contentType;
            Thread.ofVirtual().start(() -> {
                try (var reader=new BufferedReader(new InputStreamReader(input,StandardCharsets.UTF_8))) {
                    String event="message",id=""; StringBuilder data=new StringBuilder(); String line;
                    while ((line=reader.readLine())!=null) {
                        if (line.isEmpty()) { if (!data.isEmpty()) events.add(new SseFrame(event,id,data.toString())); event="message";id="";data.setLength(0); }
                        else if (line.startsWith(":")) heartbeats.incrementAndGet();
                        else if (line.startsWith("event:")) event=line.substring(6).stripLeading();
                        else if (line.startsWith("id:")) id=line.substring(3).stripLeading();
                        else if (line.startsWith("data:")) { if (!data.isEmpty()) data.append('\n'); data.append(line.substring(5).stripLeading()); }
                    }
                } catch (IOException ignored) {} finally { ended.complete(null); }
            });
        }
        SseFrame await(String name,long timeout) throws Exception {
            long end=System.nanoTime()+TimeUnit.MILLISECONDS.toNanos(timeout);
            while (System.nanoTime()<end) { var frame=events.poll(Math.max(1,TimeUnit.NANOSECONDS.toMillis(end-System.nanoTime())),TimeUnit.MILLISECONDS); if (frame!=null && frame.event.equals(name)) return frame; }
            throw new AssertionError("Missing SSE "+name+", ended="+ended.isDone());
        }
        @Override public void close() throws IOException { input.close(); }
    }
}
