package com.example.realtimechattingapi;

import com.example.realtimechattingapi.auth.*;
import com.example.realtimechattingapi.chatmessage.*;
import com.example.realtimechattingapi.global.ApiException;
import com.example.realtimechattingapi.user.*;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.*;
import org.testcontainers.junit.jupiter.*;
import org.testcontainers.mysql.MySQLContainer;
import tools.jackson.databind.ObjectMapper;

import java.net.URI;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;

import static org.assertj.core.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureMockMvc
@Testcontainers
class Chapter1IntegrationTests {
    static final String SECRET = "chapter-one-test-only-secret-at-least-32-bytes-long";
    @Container static final MySQLContainer MYSQL = new MySQLContainer("mysql:8.4");

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", MYSQL::getJdbcUrl);
        registry.add("spring.datasource.username", MYSQL::getUsername);
        registry.add("spring.datasource.password", MYSQL::getPassword);
        registry.add("jwt.secret", () -> SECRET);
        registry.add("auth.cookie-secure", () -> false);
    }

    @Autowired MockMvc mvc;
    @Autowired ObjectMapper json;
    @Autowired JdbcTemplate jdbc;
    @Autowired AuthService auth;
    @Autowired JwtTokenProvider jwt;
    @Autowired UserRepository users;
    @Autowired PasswordEncoder passwords;
    @Autowired ChatMessageRepository messages;
    @LocalServerPort int port;

    @BeforeEach
    void cleanDatabase() {
        // This is the disposable Testcontainers database, never the Compose development database.
        for (String table : List.of("chat_messages", "chat_room_members", "chat_rooms", "refresh_tokens", "users")) {
            jdbc.update("DELETE FROM " + table);
        }
    }

    @Test void mysqlAndFlywayAreRealAndSchemaIsValidated() {
        assertThat(jdbc.queryForObject("SELECT VERSION()", String.class)).startsWith("8.4.");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM flyway_schema_history WHERE version='1' AND success=1", Integer.class)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM information_schema.tables WHERE table_schema=DATABASE() AND table_name IN ('users','refresh_tokens','chat_rooms','chat_room_members','chat_messages')", Integer.class)).isEqualTo(5);
    }

    @Test void signupAndLoginSucceedAndPasswordIsHashed() throws Exception {
        signup("Alice@example.com");
        var login = login("alice@example.com");
        assertThat(jwt.verify(login.access, "access").userId()).isEqualTo(userId("alice@example.com"));
        assertThat(passwords.matches("password123", users.findByEmail("alice@example.com").orElseThrow().getPasswordHash())).isTrue();
        assertThat(login.refresh).isNotEqualTo(jdbc.queryForObject("SELECT token_hash FROM refresh_tokens", String.class));
        assertThat(jdbc.queryForObject("SELECT token_hash FROM refresh_tokens", String.class)).isEqualTo(JwtTokenProvider.hash(login.refresh));
    }

    @Test void duplicateSignupReturns409() throws Exception {
        signup("alice@example.com");
        mvc.perform(post("/api/auth/signup").with(csrf()).contentType("application/json")
                .content(signupJson("ALICE@example.com"))).andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("EMAIL_EXISTS"));
    }

    @Test void invalidSignupReturns400() throws Exception {
        mvc.perform(post("/api/auth/signup").with(csrf()).contentType("application/json")
                .content("{\"email\":\"invalid\",\"password\":\"short\",\"nickname\":\" \"}"))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("INVALID_REQUEST"));
    }

    @Test void multibytePasswordOverBcryptLimitReturns400() throws Exception {
        mvc.perform(post("/api/auth/signup").with(csrf()).contentType("application/json")
                .content(json.writeValueAsString(Map.of("email", "alice@example.com", "password", "가".repeat(25), "nickname", "alice"))))
                .andExpect(status().isBadRequest());
    }

    @Test void wrongPasswordAndUnknownEmailReturn401() throws Exception {
        signup("alice@example.com");
        for (String email : List.of("alice@example.com", "unknown@example.com")) {
            mvc.perform(post("/api/auth/login").with(csrf()).contentType("application/json")
                    .content(json.writeValueAsString(Map.of("email", email, "password", "wrongpassword"))))
                    .andExpect(status().isUnauthorized()).andExpect(jsonPath("$.code").value("UNAUTHORIZED"));
        }
    }

    @Test void missingAccessReturns401WithConsistentError() throws Exception {
        mvc.perform(get("/api/rooms")).andExpect(status().isUnauthorized())
                .andExpect(header().string("WWW-Authenticate", "Bearer"))
                .andExpect(jsonPath("$.code").value("UNAUTHORIZED"))
                .andExpect(jsonPath("$.message").isString()).andExpect(jsonPath("$.timestamp").isString())
                .andExpect(jsonPath("$.path").value("/api/rooms"));
    }

    @Test void tamperedAccessReturns401() throws Exception {
        var pair = registered("alice@example.com");
        String[] parts = pair.access.split("\\.");
        parts[2] = (parts[2].startsWith("A") ? "B" : "A") + parts[2].substring(1);
        mvc.perform(get("/api/rooms").header("Authorization", "Bearer " + String.join(".", parts)))
                .andExpect(status().isUnauthorized());
    }

    @Test void expiredAccessReturns401() throws Exception {
        registered("alice@example.com");
        mvc.perform(get("/api/rooms").header("Authorization", "Bearer " + expiredToken(userId("alice@example.com"), "access")))
                .andExpect(status().isUnauthorized());
    }

    @Test void refreshCannotBeUsedAsAccess() throws Exception {
        var pair = registered("alice@example.com");
        mvc.perform(get("/api/rooms").header("Authorization", "Bearer " + pair.refresh)).andExpect(status().isUnauthorized());
    }

    @Test void accessCannotBeUsedAsRefresh() throws Exception {
        var pair = registered("alice@example.com");
        mvc.perform(post("/api/auth/refresh").with(csrf()).cookie(new Cookie("refresh_token", pair.access)))
                .andExpect(status().isUnauthorized());
    }

    @Test void expiredAccessCanBeReplacedUsingRefreshAlone() throws Exception {
        var pair = registered("alice@example.com");
        mvc.perform(get("/api/rooms").header("Authorization", "Bearer " + expiredToken(userId("alice@example.com"), "access")))
                .andExpect(status().isUnauthorized());
        var result = refresh(pair.refresh).andExpect(status().isOk()).andReturn();
        String access = json.readTree(result.getResponse().getContentAsString()).get("accessToken").asText();
        assertThat(access).isNotEqualTo(pair.access);
        mvc.perform(get("/api/rooms").header("Authorization", "Bearer " + access)).andExpect(status().isOk());
    }

    @Test void rotationRejectsOldRefreshAndAcceptsNewRefresh() throws Exception {
        var pair = registered("alice@example.com");
        var result = refresh(pair.refresh).andExpect(status().isOk()).andReturn();
        String next = result.getResponse().getCookie("refresh_token").getValue();
        assertThat(next).isNotEqualTo(pair.refresh);
        refresh(pair.refresh).andExpect(status().isUnauthorized());
        refresh(next).andExpect(status().isOk());
    }

    @Test void expiredAndMissingRefreshReturn401() throws Exception {
        registered("alice@example.com");
        refresh(expiredToken(userId("alice@example.com"), "refresh")).andExpect(status().isUnauthorized());
        mvc.perform(post("/api/auth/refresh").with(csrf())).andExpect(status().isUnauthorized());
    }

    @Test void tamperedRefreshReturns401() throws Exception {
        var pair = registered("alice@example.com");
        refresh("invalid." + pair.refresh).andExpect(status().isUnauthorized());
    }

    @Test void logoutInvalidatesAllOldAccessAndRefreshAndAllowsNewLogin() throws Exception {
        var first = registered("alice@example.com");
        var second = login("alice@example.com");
        mvc.perform(post("/api/auth/logout").with(csrf()).header("Authorization", "Bearer " + second.access))
                .andExpect(status().isNoContent()).andExpect(cookie().maxAge("refresh_token", 0));
        for (var pair : List.of(first, second)) {
            mvc.perform(get("/api/rooms").header("Authorization", "Bearer " + pair.access)).andExpect(status().isUnauthorized());
            refresh(pair.refresh).andExpect(status().isUnauthorized());
        }
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM refresh_tokens", Integer.class)).isZero();
        assertThat(users.findByEmail("alice@example.com").orElseThrow().getTokenVersion()).isEqualTo(1);
        var next = login("alice@example.com");
        mvc.perform(get("/api/rooms").header("Authorization", "Bearer " + next.access)).andExpect(status().isOk());
        refresh(next.refresh).andExpect(status().isOk());
    }

    @Test void loginReplacesTheSingleStoredRefresh() throws Exception {
        var first = registered("alice@example.com");
        var second = login("alice@example.com");
        refresh(first.refresh).andExpect(status().isUnauthorized());
        refresh(second.refresh).andExpect(status().isOk());
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM refresh_tokens", Integer.class)).isEqualTo(1);
    }

    @Test void concurrentRefreshAllowsExactlyOneWinner() throws Exception {
        var pair = registered("alice@example.com");
        try (var executor = Executors.newFixedThreadPool(2)) {
            CountDownLatch start = new CountDownLatch(1);
            Callable<Boolean> request = () -> {
                start.await();
                try { auth.refresh(pair.refresh); return true; }
                catch (ApiException ex) {
                    assertThat(ex.getStatus().value()).isEqualTo(401);
                    return false;
                }
            };
            var a = executor.submit(request);
            var b = executor.submit(request);
            start.countDown();
            assertThat(List.of(a.get(15, TimeUnit.SECONDS), b.get(15, TimeUnit.SECONDS)))
                    .containsExactlyInAnyOrder(true, false);
        }
    }

    @Test void createRoomRegistersCreatorAndDeduplicatesParticipants() throws Exception {
        var alice = registered("alice@example.com");
        registered("bob@example.com");
        long bob = userId("bob@example.com");
        long room = room(alice, List.of(bob, bob, userId("alice@example.com")));
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM chat_room_members WHERE room_id=?", Integer.class, room)).isEqualTo(2);
    }

    @Test void roomListsAreIsolatedAndParticipantCanReadHistory() throws Exception {
        var alice = registered("alice@example.com");
        var bob = registered("bob@example.com");
        var charlie = registered("charlie@example.com");
        long room = room(alice, List.of(userId("bob@example.com")));
        for (var pair : List.of(alice, bob)) {
            mvc.perform(get("/api/rooms").header("Authorization", "Bearer " + pair.access))
                    .andExpect(status().isOk()).andExpect(jsonPath("$.items.length()").value(1));
        }
        mvc.perform(get("/api/rooms").header("Authorization", "Bearer " + charlie.access))
                .andExpect(status().isOk()).andExpect(jsonPath("$.items.length()").value(0));
        mvc.perform(get("/api/rooms/{id}/messages", room).header("Authorization", "Bearer " + bob.access))
                .andExpect(status().isOk()).andExpect(jsonPath("$.items").isEmpty());
    }

    @Test void nonParticipantHistoryReturns403() throws Exception {
        var alice = registered("alice@example.com");
        var bob = registered("bob@example.com");
        long room = room(alice, List.of());
        mvc.perform(get("/api/rooms/{id}/messages", room).header("Authorization", "Bearer " + bob.access))
                .andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("ROOM_FORBIDDEN"));
    }

    @Test void missingRoomAndUnknownParticipantReturn404() throws Exception {
        var alice = registered("alice@example.com");
        mvc.perform(get("/api/rooms/999999/messages").header("Authorization", "Bearer " + alice.access))
                .andExpect(status().isNotFound()).andExpect(jsonPath("$.code").value("ROOM_NOT_FOUND"));
        mvc.perform(post("/api/rooms").with(csrf()).header("Authorization", "Bearer " + alice.access)
                .contentType("application/json").content("{\"name\":\"test\",\"participantIds\":[999999]}"))
                .andExpect(status().isNotFound());
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM chat_rooms", Integer.class)).isZero();
    }

    @Test void historyUsesExclusiveCursorAndIsolatesRooms() throws Exception {
        var alice = registered("alice@example.com");
        long room = room(alice, List.of());
        long other = room(alice, List.of());
        long sender = userId("alice@example.com");
        var first = messages.save(new ChatMessage(room, sender, "first"));
        var second = messages.save(new ChatMessage(room, sender, "second"));
        var third = messages.save(new ChatMessage(room, sender, "third"));
        messages.save(new ChatMessage(other, sender, "other room"));
        mvc.perform(get("/api/rooms/{id}/messages?limit=2", room).header("Authorization", "Bearer " + alice.access))
                .andExpect(status().isOk()).andExpect(jsonPath("$.items[0].id").value(third.getId()))
                .andExpect(jsonPath("$.items[1].id").value(second.getId()))
                .andExpect(jsonPath("$.hasMore").value(true)).andExpect(jsonPath("$.nextCursor").value(second.getId()));
        mvc.perform(get("/api/rooms/{id}/messages?limit=2&beforeId={cursor}", room, second.getId()).header("Authorization", "Bearer " + alice.access))
                .andExpect(status().isOk()).andExpect(jsonPath("$.items.length()").value(1))
                .andExpect(jsonPath("$.items[0].id").value(first.getId())).andExpect(jsonPath("$.hasMore").value(false))
                .andExpect(jsonPath("$.nextCursor").isEmpty());
    }

    @Test void invalidLimitsAndMalformedRequestsReturn400() throws Exception {
        var alice = registered("alice@example.com");
        long room = room(alice, List.of());
        for (String query : List.of("limit=0", "limit=101", "beforeId=-1", "limit=text")) {
            mvc.perform(get("/api/rooms/{id}/messages?" + query, room).header("Authorization", "Bearer " + alice.access))
                    .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("INVALID_REQUEST"));
        }
        mvc.perform(post("/api/rooms").with(csrf()).header("Authorization", "Bearer " + alice.access)
                .contentType("application/json").content("{"))
                .andExpect(status().isBadRequest());
    }

    @Test void databaseEnforcesUniqueMembershipAndForeignKeys() throws Exception {
        var alice = registered("alice@example.com");
        long room = room(alice, List.of());
        assertThatThrownBy(() -> jdbc.update("INSERT INTO chat_room_members(room_id,user_id) VALUES (?,?)", room, userId("alice@example.com")))
                .isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
        assertThatThrownBy(() -> jdbc.update("INSERT INTO chat_messages(room_id,sender_id,content,created_at) VALUES (999999,?,'x',UTC_TIMESTAMP(6))", userId("alice@example.com")))
                .isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
    }

    @Test void csrfIsRequiredForSignupLoginRefreshAndLogout() throws Exception {
        var pair = registered("alice@example.com");
        for (String path : List.of("signup", "login", "refresh", "logout")) {
            mvc.perform(post("/api/auth/" + path).cookie(new Cookie("refresh_token", pair.refresh))
                    .header("Authorization", "Bearer " + pair.access).contentType("application/json").content("{}"))
                    .andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("FORBIDDEN"));
        }
    }

    @Test void realCsrfCookieAndHeaderAllowSignupAndRejectWrongToken() throws Exception {
        var response = mvc.perform(get("/api/auth/csrf")).andExpect(status().isOk()).andReturn().getResponse();
        Cookie cookie = response.getCookie("XSRF-TOKEN");
        String token = json.readTree(response.getContentAsString()).get("token").asText();
        assertThat(cookie.getPath()).isEqualTo("/");
        mvc.perform(post("/api/auth/signup").cookie(cookie).header("X-XSRF-TOKEN", "wrong")
                .contentType("application/json").content(signupJson("alice@example.com"))).andExpect(status().isForbidden());
        mvc.perform(post("/api/auth/signup").cookie(cookie).header("X-XSRF-TOKEN", token)
                .contentType("application/json").content(signupJson("alice@example.com"))).andExpect(status().isCreated());
    }

    @Test void refreshCookieIsHttpOnlyStrictAndNotReturnedInJson() throws Exception {
        signup("alice@example.com");
        var result = mvc.perform(post("/api/auth/login").with(csrf()).contentType("application/json")
                .content(loginJson("alice@example.com"))).andExpect(status().isOk())
                .andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(jsonPath("$.refreshToken").doesNotExist()).andReturn();
        assertThat(result.getResponse().getHeader("Set-Cookie")).contains("HttpOnly", "SameSite=Strict", "Path=/api/auth");
    }

    @Test void actualHttpServerAcceptsCsrfSignupLoginAndAuthenticatedRequest() throws Exception {
        try (HttpClient client = HttpClient.newHttpClient()) {
            String base = "http://localhost:" + port;
            var csrfResponse = client.send(HttpRequest.newBuilder(URI.create(base + "/api/auth/csrf")).GET().build(), HttpResponse.BodyHandlers.ofString());
            assertThat(csrfResponse.statusCode()).isEqualTo(200);
            String cookie = csrfResponse.headers().firstValue("set-cookie").orElseThrow().split(";", 2)[0];
            String token = json.readTree(csrfResponse.body()).get("token").asText();
            var signup = client.send(HttpRequest.newBuilder(URI.create(base + "/api/auth/signup"))
                    .header("Cookie", cookie).header("X-XSRF-TOKEN", token).header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(signupJson("http@example.com"))).build(), HttpResponse.BodyHandlers.ofString());
            assertThat(signup.statusCode()).isEqualTo(201);
            var login = client.send(HttpRequest.newBuilder(URI.create(base + "/api/auth/login"))
                    .header("Cookie", cookie).header("X-XSRF-TOKEN", token).header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(loginJson("http@example.com"))).build(), HttpResponse.BodyHandlers.ofString());
            assertThat(login.statusCode()).isEqualTo(200);
            String access = json.readTree(login.body()).get("accessToken").asText();
            var rooms = client.send(HttpRequest.newBuilder(URI.create(base + "/api/rooms"))
                    .header("Authorization", "Bearer " + access).GET().build(), HttpResponse.BodyHandlers.ofString());
            assertThat(rooms.statusCode()).isEqualTo(200);
            assertThat(json.readTree(rooms.body()).get("items").size()).isZero();
            String refreshCookie = login.headers().allValues("set-cookie").stream()
                    .filter(value -> value.startsWith("refresh_token=" )).findFirst().orElseThrow().split(";", 2)[0];
            var rotation = client.send(HttpRequest.newBuilder(URI.create(base + "/api/auth/refresh"))
                    .header("Cookie", cookie + "; " + refreshCookie).header("X-XSRF-TOKEN", token)
                    .POST(HttpRequest.BodyPublishers.noBody()).build(), HttpResponse.BodyHandlers.ofString());
            assertThat(rotation.statusCode()).isEqualTo(200);
            var reuse = client.send(HttpRequest.newBuilder(URI.create(base + "/api/auth/refresh"))
                    .header("Cookie", cookie + "; " + refreshCookie).header("X-XSRF-TOKEN", token)
                    .POST(HttpRequest.BodyPublishers.noBody()).build(), HttpResponse.BodyHandlers.ofString());
            assertThat(reuse.statusCode()).isEqualTo(401);
            var logout = client.send(HttpRequest.newBuilder(URI.create(base + "/api/auth/logout"))
                    .header("Cookie", cookie).header("X-XSRF-TOKEN", token).header("Authorization", "Bearer " + access)
                    .POST(HttpRequest.BodyPublishers.noBody()).build(), HttpResponse.BodyHandlers.ofString());
            assertThat(logout.statusCode()).isEqualTo(204);
            var denied = client.send(HttpRequest.newBuilder(URI.create(base + "/api/rooms"))
                    .header("Authorization", "Bearer " + access).GET().build(), HttpResponse.BodyHandlers.ofString());
            assertThat(denied.statusCode()).isEqualTo(401);
        }
    }

    private void signup(String email) throws Exception {
        mvc.perform(post("/api/auth/signup").with(csrf()).contentType("application/json").content(signupJson(email)))
                .andExpect(status().isCreated());
    }
    // Use the real cookie repository. Spring Security's csrf() test postprocessor
    // replaces the shared CsrfFilter repository with a session-backed test repository.
    private org.springframework.test.web.servlet.request.RequestPostProcessor csrf() throws Exception {
        var response = mvc.perform(get("/api/auth/csrf")).andExpect(status().isOk()).andReturn().getResponse();
        Cookie csrfCookie = Objects.requireNonNull(response.getCookie("XSRF-TOKEN"));
        var body = json.readTree(response.getContentAsString());
        String header = body.get("headerName").asText();
        String token = body.get("token").asText();
        return request -> {
            List<Cookie> cookies = new ArrayList<>();
            if (request.getCookies() != null) cookies.addAll(Arrays.asList(request.getCookies()));
            cookies.add(csrfCookie);
            request.setCookies(cookies.toArray(Cookie[]::new));
            request.addHeader(header, token);
            return request;
        };
    }
    private String signupJson(String email) throws Exception {
        return json.writeValueAsString(Map.of("email", email, "password", "password123", "nickname", "tester"));
    }
    private String loginJson(String email) throws Exception {
        return json.writeValueAsString(Map.of("email", email, "password", "password123"));
    }
    private Pair registered(String email) throws Exception { signup(email); return login(email); }
    private Pair login(String email) throws Exception {
        var response = mvc.perform(post("/api/auth/login").with(csrf()).contentType("application/json").content(loginJson(email)))
                .andExpect(status().isOk()).andReturn().getResponse();
        return new Pair(json.readTree(response.getContentAsString()).get("accessToken").asText(), response.getCookie("refresh_token").getValue());
    }
    private long userId(String email) { return users.findByEmail(email).orElseThrow().getId(); }
    private ResultActions refresh(String token) throws Exception {
        return mvc.perform(post("/api/auth/refresh").with(csrf()).cookie(new Cookie("refresh_token", token)));
    }
    private long room(Pair owner, List<Long> participants) throws Exception {
        var response = mvc.perform(post("/api/rooms").with(csrf()).header("Authorization", "Bearer " + owner.access)
                .contentType("application/json").content(json.writeValueAsString(Map.of("name", "test room", "participantIds", participants))))
                .andExpect(status().isCreated()).andReturn().getResponse();
        return json.readTree(response.getContentAsString()).get("id").asLong();
    }
    private String expiredToken(long userId, String purpose) {
        return Jwts.builder().issuer("realtime-chatting-api").subject(Long.toString(userId)).id(UUID.randomUUID().toString())
                .claim("purpose", purpose).claim("tokenVersion", 0L)
                .issuedAt(Date.from(Instant.now().minusSeconds(120))).expiration(Date.from(Instant.now().minusSeconds(60)))
                .signWith(Keys.hmacShaKeyFor(SECRET.getBytes(StandardCharsets.UTF_8))).compact();
    }
    record Pair(String access, String refresh) {}
}
