package com.nudge.integration;

import com.nudge.repository.DashboardRepository;
import com.nudge.service.LeadScoringService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.PageRequest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import tools.jackson.databind.ObjectMapper;

import java.net.URI;
import java.net.http.*;
import java.time.LocalDateTime;
import java.util.*;

import static org.assertj.core.api.Assertions.*;

/** Runs against an isolated PostgreSQL database provided by CI or a local test cluster. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
    "spring.flyway.enabled=true", "spring.jpa.hibernate.ddl-auto=validate",
    "jwt.secret=ZGV2T25seVNlY3JldEtleUZvck51ZGdlTG9jYWxEZXZlbG9wbWVudA==",
    "app.encryption.key=ZGV2T25seUVuY3J5cHRpb25LZXlGb3JOdWRnZTEyMzQ=",
    "app.cors.allowed-origins=https://dashboard.example", "nudge.notifications.email.enabled=false"
})
@EnabledIf("databaseAvailable")
class ApplicationIT {
    static String databaseUrl() {
        return System.getProperty("nudge.test.db.url", System.getenv("NUDGE_TEST_DB_URL"));
    }
    static boolean databaseAvailable() { return databaseUrl() != null && !databaseUrl().isBlank(); }

    @DynamicPropertySource
    static void database(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", ApplicationIT::databaseUrl);
        registry.add("spring.datasource.username", () -> System.getenv().getOrDefault("NUDGE_TEST_DB_USERNAME", "nudge_test"));
        registry.add("spring.datasource.password", () -> System.getenv().getOrDefault("NUDGE_TEST_DB_PASSWORD", ""));
    }

    @Value("${local.server.port}") int port;
    @Autowired JdbcTemplate jdbc;
    @Autowired DashboardRepository dashboard;
    @Autowired LeadScoringService scoring;
    @Autowired ObjectMapper json;
    @Autowired com.nudge.service.NotificationService notifications;
    @Autowired com.nudge.repository.TrackingEventRepository events;
    final HttpClient http = HttpClient.newHttpClient();
    static final java.util.concurrent.atomic.AtomicInteger registrations = new java.util.concurrent.atomic.AtomicInteger();

    HttpResponse<String> request(String path, String method, String body, String cookie, String origin) throws Exception {
        var builder = HttpRequest.newBuilder(URI.create("http://localhost:" + port + path))
                .header("Content-Type", "application/json");
        if (cookie != null) builder.header("Cookie", cookie);
        if (origin != null) builder.header("Origin", origin);
        return http.send(builder.method(method, HttpRequest.BodyPublishers.ofString(body)).build(), HttpResponse.BodyHandlers.ofString());
    }

    String register(String address) throws Exception {
        // Own address each time: registrations are rate-limited per IP.
        var result = http.send(HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/api/auth/register"))
                .header("Content-Type", "application/json").header("Origin", "https://dashboard.example")
                .header("X-Forwarded-For", "198.51.100." + (100 + registrations.incrementAndGet()))
                .POST(HttpRequest.BodyPublishers.ofString(json.writeValueAsString(Map.of(
                        "email", address, "password", "StrongPassword1!")))).build(), HttpResponse.BodyHandlers.ofString());
        assertThat(result.statusCode()).isEqualTo(200);
        return result.headers().firstValue("set-cookie").orElseThrow().split(";", 2)[0];
    }

    @Test
    void healthAndPackagedDashboardArePublicButAccountChangesRequireAuthentication() throws Exception {
        assertThat(request("/actuator/health", "GET", "", null, null).statusCode()).isEqualTo(200);
        assertThat(request("/index.html", "GET", "", null, null).body()).contains("Nudge");
        assertThat(request("/images/favicon.svg", "GET", "", null, null).statusCode()).isEqualTo(200);
        assertThat(request("/favicon.ico", "GET", "", null, null).statusCode()).isEqualTo(404);
        assertThat(request("/api/auth/login", "GET", "", null, null).statusCode()).isEqualTo(405);
        assertThat(request("/api/auth/password", "PUT", "{}", null, null).statusCode()).isEqualTo(401);
    }

    @Test
    void passwordRotationRenewsCookieAndRevokesPreviousToken() throws Exception {
        String oldCookie = register("rotation-" + UUID.randomUUID() + "@example.com");
        var result = request("/api/auth/password", "PUT",
                "{\"currentPassword\":\"StrongPassword1!\",\"newPassword\":\"NewStrongPassword2!\"}",
                oldCookie, "https://dashboard.example");
        assertThat(result.statusCode()).isEqualTo(200);
        String newCookie = result.headers().firstValue("set-cookie").orElseThrow().split(";", 2)[0];
        assertThat(newCookie).isNotEqualTo(oldCookie);
        assertThat(request("/api/emails", "GET", "", oldCookie, null).statusCode()).isEqualTo(401);
        assertThat(request("/api/emails", "GET", "", newCookie, null).statusCode()).isEqualTo(200);
        assertThat(request("/api/auth/logout", "POST", "", newCookie, "https://evil.example").statusCode()).isEqualTo(403);
        assertThat(request("/api/auth/logout", "POST", "", newCookie, null).statusCode()).isEqualTo(403);
        assertThat(request("/api/auth/logout", "POST", "", newCookie, "https://dashboard.example").statusCode()).isEqualTo(200);
        assertThat(request("/api/emails", "GET", "", newCookie, null).statusCode()).isEqualTo(401);
    }

    @Test
    void rememberedSessionSetsNoCookieAndIsRevokedByPasswordChange() throws Exception {
        String address = "remember-" + UUID.randomUUID() + "@example.com";
        String cookie = register(address);
        // Own address: the login rate limit is per IP and another test exhausts the shared one.
        var login = http.send(HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/api/auth/login"))
                .header("Content-Type", "application/json").header("X-Forwarded-For", "198.51.100.20")
                .POST(HttpRequest.BodyPublishers.ofString(json.writeValueAsString(Map.of(
                        "email", address, "password", "StrongPassword1!", "rememberMe", true)))).build(),
                HttpResponse.BodyHandlers.ofString());
        assertThat(login.statusCode()).isEqualTo(200);
        assertThat(login.headers().firstValue("set-cookie")).isEmpty();
        String token = json.readTree(login.body()).get("token").asString();
        assertThat(withBearer(token)).isEqualTo(200);
        assertThat(request("/api/auth/password", "PUT",
                "{\"currentPassword\":\"StrongPassword1!\",\"newPassword\":\"NewStrongPassword2!\"}",
                cookie, "https://dashboard.example").statusCode()).isEqualTo(200);
        assertThat(withBearer(token)).isEqualTo(401);
    }

    int withBearer(String token) throws Exception {
        return http.send(HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/api/emails"))
                .header("Authorization", "Bearer " + token).build(), HttpResponse.BodyHandlers.ofString()).statusCode();
    }

    @Test
    void dashboardAggregatesGloballySearchesBeyondFirstPageAndExcludesBots() {
        String owner = "stats-" + UUID.randomUUID() + "@example.com";
        Long userId = jdbc.queryForObject("INSERT INTO users(email,password_hash) VALUES (?, 'test') RETURNING id", Long.class, owner);
        LocalDateTime lastOpen = LocalDateTime.now().minusHours(6);
        Long target = null;
        for (int i = 0; i < 55; i++) {
            Long id = jdbc.queryForObject("INSERT INTO tracked_emails(user_id,subject,recipient_email,tracking_id,content) VALUES (?,?,?,?,?) RETURNING id", Long.class,
                    userId, i == 0 ? "Unique proposal" : "Mail " + i, "client@example.com", UUID.randomUUID().toString(), "encrypted");
            if (i == 0) target = id;
        }
        for (int i = 0; i < 4; i++) jdbc.update("INSERT INTO tracking_events(email_id,type,timestamp) VALUES (?, 'OPEN', ?)", target, lastOpen);
        jdbc.update("INSERT INTO tracking_events(email_id,type,timestamp) VALUES (?, 'CLICK', ?)", target, lastOpen);
        jdbc.update("INSERT INTO tracking_events(email_id,type,suspected_bot) VALUES (?, 'OPEN', true)", target);
        jdbc.update("INSERT INTO tracking_events(email_id,type,suspected_bot) VALUES (?, 'CLICK', true)", target);
        var page = dashboard.list(owner, false, "unique", "leadScore", false, PageRequest.of(0, 50));
        assertThat(page.getTotalElements()).isEqualTo(1);
        var dto = page.getContent().get(0);
        assertThat(dto.getOpenCount()).isEqualTo(4);
        assertThat(dto.getClickCount()).isEqualTo(1);
        assertThat(dto.getContent()).isNull();
        assertThat(events.countOpensByUserEmail(owner)).isEqualTo(4);
        assertThat(((Number) events.findBestSendSlot(owner, "UTC").get(0)[2]).intValue()).isEqualTo(4);
        assertThat(dto.getLeadScore()).isEqualTo(scoring.computeScore(4, 1, lastOpen));
        var stats = dashboard.stats(owner);
        assertThat(((Number) stats.get("total")).intValue()).isEqualTo(55);
        assertThat(((Number) stats.get("opened")).intValue()).isEqualTo(1);
        assertThat(dashboard.list("other@example.com", false, "", "createdAt", false, PageRequest.of(0, 50))).isEmpty();
        assertThat(dashboard.list(owner, false, "", "createdAt", false, PageRequest.of(1, 50)).getContent()).hasSize(5);

        Long clickedOnly = jdbc.queryForObject("SELECT id FROM tracked_emails WHERE user_id = ? AND subject = 'Mail 7'", Long.class, userId);
        jdbc.update("INSERT INTO tracking_events(email_id,type,timestamp) VALUES (?, 'CLICK', ?)", clickedOnly, lastOpen.plusHours(1));
        assertThat(dashboard.list(owner, false, "", "lastActivityAt", false, PageRequest.of(0, 2)).getContent())
                .extracting(com.nudge.dto.EmailDTO::getId).containsExactly(clickedOnly, target);
    }
    HttpResponse<String> load(String pixelUrl, String userAgent) throws Exception {
        String path = URI.create(pixelUrl).getPath();
        return http.send(HttpRequest.newBuilder(URI.create("http://localhost:" + port + path))
                .header("User-Agent", userAgent).build(), HttpResponse.BodyHandlers.ofString());
    }

    @Test
    void readStatusIgnoresSenderAndAppleButCountsGmail() throws Exception {
        String owner = "read-" + UUID.randomUUID() + "@example.com";
        String cookie = register(owner);
        var created = http.send(HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/api/emails"))
                .header("Content-Type", "application/json").header("Cookie", cookie)
                .header("Origin", "https://dashboard.example").header("User-Agent", "SenderBrowser")
                .POST(HttpRequest.BodyPublishers.ofString("{\"subject\":\"Hi\",\"content\":\"x\",\"recipientEmail\":\"c@example.com\"}"))
                .build(), HttpResponse.BodyHandlers.ofString());
        assertThat(created.statusCode()).isEqualTo(200);
        var dto = json.readTree(created.body()).get(0);
        String pixel = dto.get("trackingPixelUrl").asString();
        String detail = "/api/emails/" + dto.get("id").asLong();

        load(pixel, "SenderBrowser");
        assertThat(status(owner, detail, cookie)).isEqualTo("Not Opened");
        load(pixel, "Mozilla/5.0");
        assertThat(status(owner, detail, cookie)).isEqualTo("Unconfirmed");
        assertThat(((Number) dashboard.stats(owner).get("opened")).intValue()).isZero();
        assertThat(((Number) dashboard.stats(owner).get("unconfirmed")).intValue()).isEqualTo(1);
        load(pixel, "Mozilla/5.0 (Windows NT 5.1; rv:11.0) Gecko Firefox/11.0 (via ggpht.com GoogleImageProxy)");
        assertThat(status(owner, detail, cookie)).isEqualTo("Opened");
        assertThat(((Number) dashboard.stats(owner).get("opened")).intValue()).isEqualTo(1);
        assertThat(((Number) dashboard.stats(owner).get("unconfirmed")).intValue()).isZero();
    }

    @Test
    void openFromProtonMailboxIsUnconfirmed() throws Exception {
        String owner = "proton-" + UUID.randomUUID() + "@example.com";
        String cookie = register(owner);
        var created = request("/api/emails", "POST", "{\"subject\":\"Hi\",\"content\":\"x\",\"recipientEmail\":\"c@proton.me\"}", cookie, "https://dashboard.example");
        var dto = json.readTree(created.body()).get(0);
        load(dto.get("trackingPixelUrl").asString(), "Mozilla/5.0 (Windows NT 10.0) Chrome/130.0.0.0");
        assertThat(status(owner, "/api/emails/" + dto.get("id").asLong(), cookie)).isEqualTo("Unconfirmed");
    }

    @Test
    void historyShowsCountedAndIgnoredOpensToTheirOwnerOnly() throws Exception {
        String cookie = register("history-" + UUID.randomUUID() + "@example.com");
        var created = request("/api/emails", "POST", "{\"subject\":\"Hi\",\"recipientEmail\":\"c@example.com\"}", cookie, "https://dashboard.example");
        var dto = json.readTree(created.body()).get(0);
        load(dto.get("trackingPixelUrl").asString(), "Mozilla/5.0");
        load(dto.get("trackingPixelUrl").asString(), "GoogleImageProxy");
        String path = "/api/emails/" + dto.get("id").asLong() + "/events";
        var events = json.readTree(request(path, "GET", "", cookie, null).body());
        assertThat(events.size()).isEqualTo(2);
        assertThat(events.get(0).get("counted").asBoolean()).isTrue();
        assertThat(events.get(0).get("source").asString()).isEqualTo("Gmail");
        assertThat(events.get(1).get("reason").asString()).isEqualTo("APPLE_PRIVACY");
        assertThat(LocalDateTime.parse(events.get(0).get("at").asString()))
                .isCloseTo(LocalDateTime.now(java.time.ZoneOffset.UTC), within(1, java.time.temporal.ChronoUnit.MINUTES));
        assertThat(events.toString()).doesNotContain("127.0.0.1");
        String other = register("other-" + UUID.randomUUID() + "@example.com");
        assertThat(request(path, "GET", "", other, null).statusCode()).isEqualTo(403);
    }

    @Test
    void passwordResetIsPublicButUnavailableWithoutEmail() throws Exception {
        assertThat(request("/api/auth/forgot-password", "POST", "{\"email\":\"a@example.com\"}", null, null).statusCode()).isEqualTo(503);
        assertThat(request("/api/auth/reset-password", "POST", "{\"token\":\"x\",\"newPassword\":\"StrongPassword1!\"}", null, null).statusCode()).isEqualTo(400);
        assertThat(request("/reset.html", "GET", "", null, null).statusCode()).isEqualTo(200);
    }

    @Test
    void bestSendTimeUsesTheRequestedTimeZone() throws Exception {
        String owner = "zone-" + UUID.randomUUID() + "@example.com";
        String cookie = register(owner);
        Long userId = jdbc.queryForObject("SELECT id FROM users WHERE email = ?", Long.class, owner);
        Long email = jdbc.queryForObject("INSERT INTO tracked_emails(user_id,subject,tracking_id) VALUES (?, 's', ?) RETURNING id", Long.class, userId, UUID.randomUUID().toString());
        jdbc.update("INSERT INTO tracking_events(email_id,type,timestamp) VALUES (?, 'OPEN', TIMESTAMP '2026-01-05 23:30')", email); // Monday, UTC
        assertThat(request("/api/ai/send-time", "POST", "{\"timeZone\":\"Europe/Paris\"}", cookie, "https://dashboard.example").body()).contains("Tuesday at 12:00 AM");
        assertThat(request("/api/ai/send-time", "POST", "{\"timeZone\":\"nonsense\"}", cookie, "https://dashboard.example").body()).contains("Monday at 11:00 PM");
    }

    /** The detail view and the list compute the status separately: they must agree. */
    String status(String owner, String detail, String cookie) throws Exception {
        String listed = dashboard.list(owner, false, "", "status", false, PageRequest.of(0, 5)).getContent().get(0).getStatus();
        assertThat(json.readTree(request(detail, "GET", "", cookie, null).body()).get("status").asString()).isEqualTo(listed);
        return listed;
    }

    @Test
    void liveWebSocketAuthenticatesAndStopsDeliveringAfterLogout() throws Exception {
        String owner = "socket-" + UUID.randomUUID() + "@example.com";
        String cookie = register(owner);
        var frames = new java.util.concurrent.LinkedBlockingQueue<String>();
        var listener = new WebSocket.Listener() {
            final StringBuilder fragments = new StringBuilder();
            @Override public void onOpen(WebSocket socket) { socket.request(1); }
            @Override public java.util.concurrent.CompletionStage<?> onText(WebSocket socket, CharSequence text, boolean last) {
                fragments.append(text);
                if (last) { frames.add(fragments.toString()); fragments.setLength(0); }
                socket.request(1);
                return null;
            }
        };
        WebSocket socket = http.newWebSocketBuilder().header("Cookie", cookie).header("Origin", "https://dashboard.example")
                .buildAsync(URI.create("ws://localhost:" + port + "/ws/websocket"), listener).join();
        try {
            socket.sendText("CONNECT\naccept-version:1.2\nhost:localhost\n\n\0", true).join();
            assertThat(frames.poll(5, java.util.concurrent.TimeUnit.SECONDS)).startsWith("CONNECTED");
            socket.sendText("SUBSCRIBE\nid:one\ndestination:/user/queue/notifications\nreceipt:ready\n\n\0", true).join();
            var notification = new com.nudge.dto.NotificationDTO("EMAIL_CLICKED", 1L, "Proposal", "client@example.com", 1, 55, LocalDateTime.now());
            // The simple broker does not implement STOMP receipts. Retry delivery until
            // its asynchronous subscription has been installed.
            String received = null;
            for (int attempt = 0; attempt < 10 && received == null; attempt++) {
                notifications.notifyUser(owner, notification);
                received = frames.poll(500, java.util.concurrent.TimeUnit.MILLISECONDS);
            }
            assertThat(received).startsWith("MESSAGE").contains("Proposal");
            assertThat(request("/api/auth/logout", "POST", "", cookie, "https://dashboard.example").statusCode()).isEqualTo(200);
            notifications.notifyUser(owner, notification);
            assertThat(frames.poll(300, java.util.concurrent.TimeUnit.MILLISECONDS)).isNull();
            assertThatThrownBy(() -> http.newWebSocketBuilder().header("Cookie", cookie).header("Origin", "https://dashboard.example")
                    .buildAsync(URI.create("ws://localhost:" + port + "/ws/websocket"), listener).join())
                    .hasCauseInstanceOf(WebSocketHandshakeException.class);
        } finally { socket.abort(); }
    }

    @Test
    void loginRateLimitConsumesExactlyOneTokenPerRequest() throws Exception {
        for (int attempt = 0; attempt < 10; attempt++) {
            assertThat(request("/api/auth/login", "POST", "{\"email\":\"missing@example.com\",\"password\":\"wrong\"}", null, null).statusCode()).isEqualTo(401);
        }
        assertThat(request("/api/auth/login", "POST", "{\"email\":\"missing@example.com\",\"password\":\"wrong\"}", null, null).statusCode()).isEqualTo(429);
    }

}
