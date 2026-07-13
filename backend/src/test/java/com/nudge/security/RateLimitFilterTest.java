package com.nudge.security;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests for RateLimitFilter.
 *
 * Key invariants:
 *  S5 — requests are blocked once the per-IP limit is exceeded.
 *  S4 — X-Forwarded-For is only trusted from configured trusted proxy ranges.
 *        Without this, an attacker could rotate the header to bypass rate limiting.
 */
class RateLimitFilterTest {

    private static final String LOGIN_PATH    = "/api/auth/login";
    private static final String REGISTER_PATH = "/api/auth/register";

    private RateLimitFilter filter;

    @BeforeEach
    void setUp() {
        // No trusted proxies by default — XFF is ignored
        filter = new RateLimitFilter(Set.of());
    }

    // ── rate limiting ─────────────────────────────────────────────────────

    @Test
    void allowsRequests_belowLimit_forRegister() throws Exception {
        for (int i = 0; i < 5; i++) {
            MockHttpServletResponse resp = doPost(REGISTER_PATH, "7.7.7.1", null);
            assertThat(resp.getStatus()).isNotEqualTo(HttpStatus.TOO_MANY_REQUESTS.value());
        }
    }

    @Test
    void blocks_request_whenRegisterLimitExceeded() throws Exception {
        // Exhaust the 5-request register limit
        for (int i = 0; i < 5; i++) {
            doPost(REGISTER_PATH, "7.7.7.2", null);
        }
        // 6th request must be blocked
        MockHttpServletResponse resp = doPost(REGISTER_PATH, "7.7.7.2", null);
        assertThat(resp.getStatus()).isEqualTo(HttpStatus.TOO_MANY_REQUESTS.value());
    }

    @Test
    void registerLimit_isTrackedSeparatelyFromLoginLimit() throws Exception {
        // Exhaust the login bucket for this IP
        for (int i = 0; i < 10; i++) {
            doPost(LOGIN_PATH, "7.7.7.3", null);
        }
        // The register bucket for the same IP must be unaffected
        MockHttpServletResponse resp = doPost(REGISTER_PATH, "7.7.7.3", null);
        assertThat(resp.getStatus()).isNotEqualTo(HttpStatus.TOO_MANY_REQUESTS.value());
    }

    @Test
    void blocks_trackOpen_whenPerTrackingIdLimitExceeded() throws Exception {
        // /track/open is keyed on the tracking ID, not the caller IP — a different
        // IP per request must not reset the bucket (prevents open-count inflation).
        for (int i = 0; i < 30; i++) {
            doGet("/track/open/shared-tracking-id", "8.8.8." + i);
        }
        MockHttpServletResponse resp = doGet("/track/open/shared-tracking-id", "8.8.8.99");
        assertThat(resp.getStatus()).isEqualTo(HttpStatus.TOO_MANY_REQUESTS.value());
    }

    @Test
    void trackOpen_limitIsPerTrackingId_doesNotAffectOtherTrackingIds() throws Exception {
        for (int i = 0; i < 30; i++) {
            doGet("/track/open/tracking-id-a", "9.9.9.1");
        }
        MockHttpServletResponse resp = doGet("/track/open/tracking-id-b", "9.9.9.1");
        assertThat(resp.getStatus()).isNotEqualTo(HttpStatus.TOO_MANY_REQUESTS.value());
    }

    @Test
    void allowsRequests_belowLimit() throws Exception {
        for (int i = 0; i < 10; i++) {
            MockHttpServletResponse resp = doPost(LOGIN_PATH, "1.1.1.1", null);
            assertThat(resp.getStatus()).isNotEqualTo(HttpStatus.TOO_MANY_REQUESTS.value());
        }
    }

    @Test
    void blocks_request_whenLimitExceeded() throws Exception {
        // Exhaust the 10-request login limit
        for (int i = 0; i < 10; i++) {
            doPost(LOGIN_PATH, "2.2.2.2", null);
        }
        // 11th request must be blocked
        MockHttpServletResponse resp = doPost(LOGIN_PATH, "2.2.2.2", null);
        assertThat(resp.getStatus()).isEqualTo(HttpStatus.TOO_MANY_REQUESTS.value());
    }

    @Test
    void rateLimitIsPerIp_doesNotAffectOtherIps() throws Exception {
        // Exhaust limit for 3.3.3.3
        for (int i = 0; i < 10; i++) {
            doPost(LOGIN_PATH, "3.3.3.3", null);
        }
        // A different IP must still be allowed
        MockHttpServletResponse resp = doPost(LOGIN_PATH, "3.3.3.4", null);
        assertThat(resp.getStatus()).isNotEqualTo(HttpStatus.TOO_MANY_REQUESTS.value());
    }

    @Test
    void doesNotRateLimit_nonProtectedPath() throws Exception {
        // A GET to /api/emails should pass through with no rate limiting
        MockHttpServletRequest req = new MockHttpServletRequest("GET", "/api/emails");
        req.setRemoteAddr("4.4.4.4");
        MockHttpServletResponse resp = new MockHttpServletResponse();

        for (int i = 0; i < 50; i++) {
            filter.doFilter(req, resp, new MockFilterChain());
        }
        assertThat(resp.getStatus()).isNotEqualTo(HttpStatus.TOO_MANY_REQUESTS.value());
    }

    // ── IP spoofing protection ────────────────────────────────────────────

    @Test
    void S4_ignoresXFF_whenNotFromTrustedProxy() throws Exception {
        // 10 requests from "real" IP 5.5.5.5, each with a different spoofed XFF
        for (int i = 0; i < 10; i++) {
            doPost(LOGIN_PATH, "5.5.5.5", "192.168.0." + i);  // rotating spoofed IPs
        }
        // If XFF were trusted, each request would look like a fresh IP and the
        // 11th would succeed. With XFF ignored, it must be blocked.
        MockHttpServletResponse resp = doPost(LOGIN_PATH, "5.5.5.5", "192.168.0.99");
        assertThat(resp.getStatus()).isEqualTo(HttpStatus.TOO_MANY_REQUESTS.value());
    }

    @Test
    void S4_usesXFF_whenFromTrustedProxy() throws Exception {
        RateLimitFilter filterWithProxy = new RateLimitFilter(Set.of("10.0.0.0/8"));

        // First request from behind trusted proxy 10.0.0.1, real client 6.6.6.6
        MockHttpServletResponse resp = doPostWith(filterWithProxy, LOGIN_PATH, "10.0.0.1", "6.6.6.6");
        assertThat(resp.getStatus()).isNotEqualTo(HttpStatus.TOO_MANY_REQUESTS.value());

        // Same client IP from a different proxy should exhaust that client's bucket
        for (int i = 0; i < 9; i++) {
            doPostWith(filterWithProxy, LOGIN_PATH, "10.0.0.2", "6.6.6.6");
        }
        MockHttpServletResponse blocked = doPostWith(filterWithProxy, LOGIN_PATH, "10.0.0.1", "6.6.6.6");
        assertThat(blocked.getStatus()).isEqualTo(HttpStatus.TOO_MANY_REQUESTS.value());
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private MockHttpServletResponse doPost(String path, String remoteAddr, String xff)
            throws Exception {
        return doPostWith(filter, path, remoteAddr, xff);
    }

    private MockHttpServletResponse doGet(String path, String remoteAddr) throws Exception {
        MockHttpServletRequest req = new MockHttpServletRequest("GET", path);
        req.setRemoteAddr(remoteAddr);
        MockHttpServletResponse resp = new MockHttpServletResponse();
        filter.doFilter(req, resp, new MockFilterChain());
        return resp;
    }

    private MockHttpServletResponse doPostWith(RateLimitFilter f, String path,
                                               String remoteAddr, String xff)
            throws Exception {
        MockHttpServletRequest req = new MockHttpServletRequest("POST", path);
        req.setRemoteAddr(remoteAddr);
        if (xff != null) req.addHeader("X-Forwarded-For", xff);
        MockHttpServletResponse resp = new MockHttpServletResponse();
        f.doFilter(req, resp, new MockFilterChain());
        return resp;
    }
}
