package com.example.realtimechattingapi;

import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import tools.jackson.databind.ObjectMapper;

import javax.net.ssl.*;
import java.io.InputStream;
import java.net.*;
import java.net.http.*;
import java.nio.file.*;
import java.security.KeyStore;
import java.security.cert.*;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;

import static org.assertj.core.api.Assertions.*;

/** 실제 실행 중인 Nginx·앱·MySQL 대상. 생성한 fixture는 남기며 기존 데이터는 삭제하지 않는다. */
@EnabledIfEnvironmentVariable(named = "CHAPTER4_BASE_URL", matches = "https://.+")
@EnabledIfEnvironmentVariable(named = "CHAPTER4_CA_CERT", matches = ".+")
@EnabledIfEnvironmentVariable(named = "CHAPTER4_HTTP_URL", matches = "http://.+")
class Chapter4ProxyTests {
    private static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(8);
    private static final String PASSWORD = "chapter4-test-only-" + UUID.randomUUID();
    private final ObjectMapper json = new ObjectMapper();
    private final List<HttpClient> clients = new ArrayList<>();
    private final List<Chapter2WebSocketTests.WireClient> sockets = new ArrayList<>();
    private final List<Chapter3RecoveryTests.SseWire> streams = new ArrayList<>();
    private URI base;
    private SSLContext tls;

    @BeforeEach void configureTrust() throws Exception {
        base = URI.create(System.getenv("CHAPTER4_BASE_URL").replaceAll("/$", ""));
        var certificates = CertificateFactory.getInstance("X.509");
        var store = KeyStore.getInstance(KeyStore.getDefaultType());
        store.load(null, null);
        try (InputStream input = Files.newInputStream(Path.of(System.getenv("CHAPTER4_CA_CERT")))) {
            int index = 0;
            for (var certificate : certificates.generateCertificates(input)) {
                store.setCertificateEntry("chapter4-local-ca-" + index++, certificate);
            }
            assertThat(index).as("CA PEM contains certificates").isPositive();
        }
        var trust = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
        trust.init(store);
        tls = SSLContext.getInstance("TLS");
        tls.init(null, trust.getTrustManagers(), null);
        // 기본 HTTPS 호스트명 검증을 유지하고 trust-all/hostname 우회는 사용하지 않는다.
    }

    @AfterEach void closeConnections() throws Exception {
        for (var socket : sockets) socket.abort();
        for (var stream : streams) stream.close();
        for (var client : clients) client.close();
    }

    @Test void trustedHttpsValidatesCertificateAndHttpRedirectsWithoutServingTheApp() throws Exception {
        var http = client();
        var response = http.send(request("/chat-test.html").GET().build(), HttpResponse.BodyHandlers.ofString());
        assertThat(response.statusCode()).isEqualTo(200);
        var session = response.sslSession().orElseThrow();
        assertThat(session.getProtocol()).isIn("TLSv1.2", "TLSv1.3");
        var certificate = (X509Certificate) session.getPeerCertificates()[0];
        certificate.checkValidity();
        assertThat(certificate.getSubjectAlternativeNames()).isNotEmpty();
        System.out.printf("Verified HTTPS protocol=%s cipher=%s subject=%s notAfter=%s%n",
                session.getProtocol(), session.getCipherSuite(), certificate.getSubjectX500Principal(), certificate.getNotAfter());
        var redirected = http.send(HttpRequest.newBuilder(URI.create(System.getenv("CHAPTER4_HTTP_URL")
                + "/chat-test.html?source=chapter4")).timeout(REQUEST_TIMEOUT).GET().build(), HttpResponse.BodyHandlers.ofString());
        assertThat(redirected.statusCode()).isIn(301, 308);
        assertThat(redirected.headers().firstValue("location")).contains(base + "/chat-test.html?source=chapter4");
    }

    @Test void untrustedCaFailsTheTlsHandshake() {
        var untrusted = HttpClient.newBuilder().connectTimeout(REQUEST_TIMEOUT).build();
        clients.add(untrusted);
        assertThatThrownBy(() -> untrusted.send(request("/chat-test.html").GET().build(), HttpResponse.BodyHandlers.discarding()))
                .isInstanceOf(SSLHandshakeException.class);
    }

    @Test void httpsCookiesKeepSecureCsrfAndRefreshRotation() throws Exception {
        var account = account("cookie");
        var csrfCookie = account.cookies.getCookieStore().getCookies().stream()
                .filter(cookie -> cookie.getName().equals("XSRF-TOKEN")).findFirst().orElseThrow();
        var refreshCookie = account.cookies.getCookieStore().getCookies().stream()
                .filter(cookie -> cookie.getName().equals("refresh_token")).findFirst().orElseThrow();
        assertThat(csrfCookie.getSecure()).isTrue();
        assertThat(refreshCookie.getSecure()).isTrue();
        assertThat(refreshCookie.isHttpOnly()).isTrue();
        assertThat(refreshCookie.getPath()).isEqualTo("/api/auth");
        assertThat(account.login.headers().allValues("set-cookie").stream().anyMatch(value -> value.contains("SameSite=Strict"))).isTrue();

        var noCsrf = account.http.send(request("/api/auth/refresh").POST(HttpRequest.BodyPublishers.noBody()).build(), HttpResponse.BodyHandlers.ofString());
        assertThat(noCsrf.statusCode()).isEqualTo(403);
        String oldRefresh = refreshCookie.getValue();
        String oldAccess = account.access;
        var rotated = mutate(account, "/api/auth/refresh", null, false);
        assertThat(rotated.statusCode()).isEqualTo(200);
        account.access = json.readTree(rotated.body()).get("accessToken").asText();
        assertThat(account.access.equals(oldAccess)).isFalse();
        assertThat(get(account, "/api/rooms?limit=1").statusCode()).isEqualTo(200);

        var csrf = csrf(account);
        account.cookies.getCookieStore().getCookies().stream().filter(cookie -> cookie.getName().equals("refresh_token"))
                .forEach(cookie -> cookie.setValue(oldRefresh));
        var replay = account.http.send(request("/api/auth/refresh")
                .header(csrf.header, csrf.token).POST(HttpRequest.BodyPublishers.noBody()).build(), HttpResponse.BodyHandlers.ofString());
        assertThat(replay.statusCode()).isEqualTo(401);
    }

    @Test void actualWssExchangesStoredMessagesAndStillBlocksNonMembers() throws Exception {
        var a = account("wss-a"); var b = account("wss-b");
        long room = createRoom(a, b.id);
        var sender = connected(a); var receiver = connected(b);
        subscribe(sender, room); subscribe(receiver, room);
        // 이력 API 왕복도 확인. SUBSCRIBE가 브로커 등록 완료 영수증이라는 보장은 하지 않는다.
        assertThat(get(b, "/api/rooms/" + room + "/messages?afterId=0").statusCode()).isEqualTo(200);
        send(sender, room, "chapter4-over-wss");
        var own = json.readTree(sender.await("MESSAGE", 5000).body());
        var received = json.readTree(receiver.await("MESSAGE", 5000).body());
        assertThat(received.get("id").asLong()).isEqualTo(own.get("id").asLong());
        assertThat(received.get("senderId").asLong()).isEqualTo(a.id);
        var history = json.readTree(get(b, "/api/rooms/" + room + "/messages?afterId=0").body());
        assertThat(history.get("items").get(0).get("id").asLong()).isEqualTo(received.get("id").asLong());
        var outsider = account("outsider");
        assertThat(get(outsider, "/api/rooms/" + room + "/messages?afterId=0").statusCode()).isEqualTo(403);
        var forbidden = connected(outsider); subscribe(forbidden, room);
        forbidden.awaitClosed(5000);
        assertThat(forbidden.messagesReceived).isZero();
    }

    @Test void wssRejectsForeignOriginAndUnauthenticatedStompConnect() throws Exception {
        var account = account("origin");
        var csrf = csrf(account);
        var foreign = new Chapter2WebSocketTests.WireClient(csrf.header, csrf.token);
        assertThatThrownBy(() -> account.http.newWebSocketBuilder().header("Origin", "https://foreign.invalid")
                .subprotocols("v12.stomp").buildAsync(wsUri(), foreign).get(8, TimeUnit.SECONDS))
                .hasCauseInstanceOf(WebSocketHandshakeException.class);
        var anonymous = upgraded(account); anonymous.connect(null, true);
        anonymous.awaitClosed(5000);
        assertThat(anonymous.seenConnected).isFalse();
    }

    @Test void wssRequiresConnectCsrfEvenWhenTheJwtIsValid() throws Exception {
        var account = account("connect-csrf");
        var socket = upgraded(account); socket.connect(account.access, false);
        socket.awaitClosed(5000);
        assertThat(socket.seenConnected).isFalse();
    }

    @Test void spoofedForwardedHeadersCannotOverrideProxyOrigin() throws Exception {
        var account = account("spoofed-forwarded");
        var csrf = csrf(account);
        var socket = new Chapter2WebSocketTests.WireClient(csrf.header, csrf.token);
        sockets.add(socket);
        socket.socket = account.http.newWebSocketBuilder()
                .header("Origin", base.toString())
                .header("Forwarded", "proto=http;host=foreign.invalid")
                .header("X-Forwarded-Host", "foreign.invalid")
                .header("X-Forwarded-Proto", "http")
                .header("X-Forwarded-Port", "81")
                .header("X-Forwarded-For", "192.0.2.123")
                .header("X-Forwarded-Prefix", "/injected")
                .subprotocols("v12.stomp").buildAsync(wsUri(), socket).get(8, TimeUnit.SECONDS);
        socket.connect(account.access, true);
        socket.await("CONNECTED", 5000);
        var request = request("/api/rooms?limit=1").header("Authorization", "Bearer " + account.access)
                .header("Forwarded", "proto=http;host=foreign.invalid")
                .header("X-Forwarded-Host", "foreign.invalid")
                .header("X-Forwarded-Proto", "http")
                .header("X-Forwarded-Port", "81")
                .header("X-Forwarded-Prefix", "/injected").GET().build();
        assertThat(account.http.send(request, HttpResponse.BodyHandlers.ofString()).statusCode()).isEqualTo(200);
    }

    @Test void httpsSseStreamsOnlyToRecipientAndRecoversOfflineInvites() throws Exception {
        var a = account("sse-a"); var b = account("sse-b");
        var senderStream = stream(a); var receiverStream = stream(b);
        long firstRoom = createRoom(a, b.id);
        var first = receiverStream.await("notification", 5000);
        var notification = json.readTree(first.data());
        assertThat(notification.get("roomId").asLong()).isEqualTo(firstRoom);
        long checkpoint = notification.get("id").asLong();
        assertThat(Long.parseLong(first.id())).isEqualTo(checkpoint);
        assertThat(senderStream.events.poll(350, TimeUnit.MILLISECONDS)).isNull();
        assertThat(json.readTree(get(a, "/api/notifications?afterId=0").body()).get("items").size()).isZero();
        receiverStream.close();
        long offlineOne = createRoom(a, b.id); long offlineTwo = createRoom(a, b.id);
        var reconnected = stream(b);
        assertThat(reconnected.events.poll(350, TimeUnit.MILLISECONDS)).isNull();
        // 과거 이벤트 자동 replay 대신 아래 HTTP 조회로 복구.
        var recovered = json.readTree(get(b, "/api/notifications?afterId=" + checkpoint).body());
        assertThat(recovered.get("items").size()).isEqualTo(2);
        assertThat(recovered.get("items").get(0).get("roomId").asLong()).isEqualTo(offlineOne);
        assertThat(recovered.get("items").get(1).get("roomId").asLong()).isEqualTo(offlineTwo);
        assertThat(recovered.get("items").get(0).get("id").asLong()).isGreaterThan(checkpoint);
    }

    @Test void logoutThroughHttpsEndsWssAndSseAndRejectsOldAccessAndRefresh() throws Exception {
        var account = account("logout");
        var socket = connected(account); var stream = stream(account);
        String oldRefresh = account.cookies.getCookieStore().getCookies().stream()
                .filter(cookie -> cookie.getName().equals("refresh_token")).findFirst().orElseThrow().getValue();
        var logout = mutate(account, "/api/auth/logout", null, true);
        assertThat(logout.statusCode()).isEqualTo(204);
        socket.awaitClosed(5000); stream.ended.get(5, TimeUnit.SECONDS);
        assertThat(get(account, "/api/rooms?limit=1").statusCode()).isEqualTo(401);
        assertThat(get(account, "/api/notifications/stream").statusCode()).isEqualTo(401);
        var replay = new HttpCookie("refresh_token", oldRefresh);
        replay.setPath("/api/auth"); replay.setSecure(true); replay.setHttpOnly(true); replay.setVersion(0);
        account.cookies.getCookieStore().add(base, replay);
        assertThat(mutate(account, "/api/auth/refresh", null, false).statusCode()).isEqualTo(401);
        var old = upgraded(account); old.connect(account.access, true); old.awaitClosed(5000);
        assertThat(old.seenConnected).isFalse();
    }

    private HttpClient client() {
        var client = HttpClient.newBuilder().sslContext(tls).connectTimeout(REQUEST_TIMEOUT)
                .followRedirects(HttpClient.Redirect.NEVER).version(HttpClient.Version.HTTP_1_1).build();
        clients.add(client); return client;
    }

    private Account account(String role) throws Exception {
        var cookies = new CookieManager(null, CookiePolicy.ACCEPT_ALL);
        var client = HttpClient.newBuilder().sslContext(tls).cookieHandler(cookies).connectTimeout(REQUEST_TIMEOUT)
                .followRedirects(HttpClient.Redirect.NEVER).version(HttpClient.Version.HTTP_1_1).build();
        clients.add(client);
        var account = new Account(client, cookies);
        String email = "ch4-" + role + "-" + UUID.randomUUID() + "@example.com";
        var signup = mutate(account, "/api/auth/signup", Map.of("email", email, "password", PASSWORD, "nickname", role), false);
        assertThat(signup.statusCode()).isEqualTo(201);
        account.id = json.readTree(signup.body()).get("id").asLong();
        account.login = mutate(account, "/api/auth/login", Map.of("email", email, "password", PASSWORD), false);
        assertThat(account.login.statusCode()).isEqualTo(200);
        account.access = json.readTree(account.login.body()).get("accessToken").asText();
        return account;
    }

    private HttpRequest.Builder request(String path) { return HttpRequest.newBuilder(base.resolve(path)).timeout(REQUEST_TIMEOUT); }
    private HttpResponse<String> get(Account account, String path) throws Exception {
        return account.http.send(request(path).header("Authorization", "Bearer " + account.access).GET().build(), HttpResponse.BodyHandlers.ofString());
    }
    private Csrf csrf(Account account) throws Exception {
        var result = account.http.send(request("/api/auth/csrf").GET().build(), HttpResponse.BodyHandlers.ofString());
        assertThat(result.statusCode()).isEqualTo(200);
        var data = json.readTree(result.body()); return new Csrf(data.get("headerName").asText(), data.get("token").asText());
    }
    private HttpResponse<String> mutate(Account account, String path, Object body, boolean authenticated) throws Exception {
        var csrf = csrf(account); var request = request(path).header(csrf.header, csrf.token);
        if (authenticated) request.header("Authorization", "Bearer " + account.access);
        if (body != null) request.header("Content-Type", "application/json");
        request.POST(body == null ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofString(json.writeValueAsString(body)));
        return account.http.send(request.build(), HttpResponse.BodyHandlers.ofString());
    }
    private long createRoom(Account account, long participant) throws Exception {
        var result = mutate(account, "/api/rooms", Map.of("name", "Chapter4 TLS fixture", "participantIds", List.of(participant)), true);
        assertThat(result.statusCode()).isEqualTo(201); return json.readTree(result.body()).get("id").asLong();
    }
    private URI wsUri() { return URI.create("wss://" + base.getRawAuthority() + "/ws"); }
    private Chapter2WebSocketTests.WireClient upgraded(Account account) throws Exception {
        var csrf = csrf(account);
        var socket = new Chapter2WebSocketTests.WireClient(csrf.header, csrf.token); sockets.add(socket);
        socket.socket = account.http.newWebSocketBuilder().header("Origin", base.toString()).subprotocols("v12.stomp")
                .buildAsync(wsUri(), socket).get(8, TimeUnit.SECONDS);
        return socket;
    }
    private Chapter2WebSocketTests.WireClient connected(Account account) throws Exception {
        var socket = upgraded(account); socket.connect(account.access, true); socket.await("CONNECTED", 5000); return socket;
    }
    private void subscribe(Chapter2WebSocketTests.WireClient socket, long room) {
        socket.frame("SUBSCRIBE", Map.of("id", "room", "destination", "/topic/rooms/" + room), "");
    }
    private void send(Chapter2WebSocketTests.WireClient socket, long room, String content) {
        socket.frame("SEND", Map.of("destination", "/app/rooms/" + room + "/messages", "content-type", "application/json"), json.writeValueAsString(Map.of("content", content)));
    }
    private Chapter3RecoveryTests.SseWire stream(Account account) throws Exception {
        var result = account.http.send(request("/api/notifications/stream").header("Authorization", "Bearer " + account.access).GET().build(), HttpResponse.BodyHandlers.ofInputStream());
        assertThat(result.statusCode()).isEqualTo(200);
        String type = result.headers().firstValue("content-type").orElse("");
        assertThat(type).startsWith("text/event-stream");
        var stream = new Chapter3RecoveryTests.SseWire(result.body(), type); streams.add(stream);
        stream.await("ready", 5000); return stream;
    }
    private record Csrf(String header, String token) {}
    private static final class Account {
        final HttpClient http; final CookieManager cookies;
        long id; String access; HttpResponse<String> login;
        Account(HttpClient http, CookieManager cookies) { this.http = http; this.cookies = cookies; }
    }
}
