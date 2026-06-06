package com.nudge.security;

import jakarta.servlet.*;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import com.nudge.util.IpUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.HttpStatus;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Simple token-bucket rate limiter applied to sensitive endpoints.
 *
 * Protected routes:
 *   POST /api/auth/login    — 10 requests per minute per IP (brute-force guard)
 *   POST /api/auth/register — 5 requests per minute per IP
 *   GET  /track/open/**     — 30 requests per minute per tracking-ID (inflation guard)
 *
 * Implementation: sliding-window counter keyed on (IP|trackingId) with 60-second reset.
 * This is an in-memory, single-node implementation — suitable for MVP.
 * Replace with Redis + Bucket4j for multi-instance deployments.
 *
 * X-Forwarded-For is only trusted when the direct connection originates from a
 *     configured trusted proxy range — same logic as TrackingService.extractIp().
 *     Without this check an attacker could spoof their IP to bypass rate limiting.
 */
@Component
public class RateLimitFilter implements Filter {

    private static final Logger log = LoggerFactory.getLogger(RateLimitFilter.class);

    private final Set<String> trustedProxies;

    public RateLimitFilter(@Qualifier("trustedProxySet") Set<String> trustedProxies) {
        this.trustedProxies = trustedProxies;
    }

    private static final long WINDOW_MS = 60_000L;

    /** Max attempts per window for each protected path pattern */
    private static final int LOGIN_LIMIT    = 10;
    private static final int REGISTER_LIMIT = 5;
    private static final int TRACK_LIMIT    = 30;

    private static final class RateBucket {
        long count;
        long windowStart;
        RateBucket(long now) { this.count = 0L; this.windowStart = now; }
    }

    private final Map<String, RateBucket> counters = new ConcurrentHashMap<>();

    @Override
    public void doFilter(ServletRequest req, ServletResponse res, FilterChain chain)
            throws IOException, ServletException {

        HttpServletRequest  request  = (HttpServletRequest)  req;
        HttpServletResponse response = (HttpServletResponse) res;

        String path   = request.getRequestURI();
        String method = request.getMethod();

        String key;
        int    limit;

        if ("POST".equals(method) && path.equals("/api/auth/login")) {
            key   = "login:"    + getClientIp(request);
            limit = LOGIN_LIMIT;
        } else if ("POST".equals(method) && path.equals("/api/auth/register")) {
            key   = "register:" + getClientIp(request);
            limit = REGISTER_LIMIT;
        } else if ("GET".equals(method) && path.startsWith("/track/open/")) {
            // Key on the tracking ID itself — not the caller IP — to prevent open-count inflation
            String trackingId = path.substring("/track/open/".length());
            key   = "track-open:" + trackingId;
            limit = TRACK_LIMIT;
        } else if ("GET".equals(method) && path.startsWith("/track/click/")) {
            // Same inflation guard for click events
            String trackingId = path.substring("/track/click/".length());
            key   = "track-click:" + trackingId;
            limit = TRACK_LIMIT;
        } else {
            chain.doFilter(req, res);
            return;
        }

        if (isRateLimited(key, limit)) {
            log.warn("Rate limit exceeded for key={}", key);
            response.setStatus(HttpStatus.TOO_MANY_REQUESTS.value());
            response.setContentType("application/json");
            response.getWriter().write("{\"error\":\"Too many requests — please slow down.\"}");
            return;
        }

        chain.doFilter(req, res);
    }

    private boolean isRateLimited(String key, int limit) {
        long now = System.currentTimeMillis();
        RateBucket bucket = counters.computeIfAbsent(key, k -> new RateBucket(now));

        synchronized (bucket) {
            if (now - bucket.windowStart > WINDOW_MS) {
                bucket.count       = 0L;
                bucket.windowStart = now;
            }
            bucket.count++;
            return bucket.count > limit;
        }
    }

    /** Purge expired windows every 10 minutes to prevent unbounded memory growth. */
    @Scheduled(fixedDelay = 600_000)
    void evictExpiredEntries() {
        long now = System.currentTimeMillis();
        int before = counters.size();
        counters.entrySet().removeIf(entry -> {
            synchronized (entry.getValue()) {
                return now - entry.getValue().windowStart > WINDOW_MS;
            }
        });
        int removed = before - counters.size();
        if (removed > 0) log.debug("RateLimitFilter evicted {} expired entries", removed);
    }

    /**
     * Only trust X-Forwarded-For when the direct connection comes from a
     * configured trusted proxy range. Mirrors TrackingService.extractIp() so
     * the two are consistent and neither can be trivially bypassed by IP spoofing.
     */
    private String getClientIp(HttpServletRequest request) {
        String remoteAddr = request.getRemoteAddr();
        if (isTrustedProxy(remoteAddr)) {
            String xff = request.getHeader("X-Forwarded-For");
            if (xff != null && !xff.isBlank()) {
                return xff.split(",")[0].trim();
            }
        }
        return remoteAddr;
    }

    private boolean isTrustedProxy(String ip) {
        if (ip == null) return false;
        for (String trusted : trustedProxies) {
            if (IpUtils.matches(ip, trusted)) return true;
        }
        return false;
    }
}
