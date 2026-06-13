package com.nudge.security;

import io.github.bucket4j.Bandwidth;
import io.github.bucket4j.Bucket;
import io.github.bucket4j.Refill;
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
import java.time.Duration;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Token-bucket rate limiter applied to sensitive endpoints (Bucket4j).
 *
 * Protected routes:
 *   POST /api/auth/login    — 10 requests per minute per IP (brute-force guard)
 *   POST /api/auth/register — 5 requests per minute per IP
 *   GET  /track/open/**     — 30 requests per minute per tracking-ID (inflation guard)
 *
 * Token-bucket semantics (Bucket4j): each key starts with a full bucket of capacity N.
 * Tokens refill greedily at N/minute. A request that finds an empty bucket is rejected
 * with 429 — no token is consumed, so the caller cannot probe by counting rejections.
 *
 * To migrate to Redis for multi-instance deployments, replace the in-memory BucketEntry
 * map with a Bucket4j ProxyManager backed by a Redisson or Lettuce Redis connection.
 * The doFilter logic stays identical — only the bucket factory changes.
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

    /** Max tokens per minute for each protected path pattern */
    private static final int LOGIN_LIMIT    = 10;
    private static final int REGISTER_LIMIT = 5;
    private static final int TRACK_LIMIT    = 30;

    /** Wraps a Bucket4j Bucket with a last-used timestamp for eviction. */
    private static final class BucketEntry {
        final Bucket bucket;
        volatile long lastUsedMs;

        BucketEntry(int capacity) {
            this.bucket = Bucket.builder()
                    .addLimit(Bandwidth.classic(capacity,
                            Refill.greedy(capacity, Duration.ofMinutes(1))))
                    .build();
            this.lastUsedMs = System.currentTimeMillis();
        }
    }

    private final Map<String, BucketEntry> buckets = new ConcurrentHashMap<>();

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

    /**
     * Tries to consume one token from the bucket for the given key.
     * Returns true (= rate limited) if no token is available.
     * Bucket4j's tryConsume is thread-safe — no external synchronisation needed.
     *
     * To migrate to Redis: replace `buckets.computeIfAbsent(...)` with a call to
     * a Bucket4j Redis ProxyManager. The rest of this method stays identical.
     */
    private boolean isRateLimited(String key, int capacity) {
        BucketEntry entry = buckets.computeIfAbsent(key, k -> new BucketEntry(capacity));
        entry.lastUsedMs = System.currentTimeMillis();
        return !entry.bucket.tryConsume(1);
    }

    /** Evict entries idle for more than 2 minutes to bound memory usage. */
    @Scheduled(fixedDelay = 600_000)
    void evictExpiredEntries() {
        long cutoff = System.currentTimeMillis() - WINDOW_MS * 2;
        int before = buckets.size();
        buckets.entrySet().removeIf(e -> e.getValue().lastUsedMs < cutoff);
        int removed = before - buckets.size();
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
