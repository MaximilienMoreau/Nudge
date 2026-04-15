package com.nudge.security;

import jakarta.servlet.*;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * S5: Simple token-bucket rate limiter applied to sensitive endpoints.
 *
 * Protected routes:
 *   POST /api/auth/login    — 10 requests per minute per IP (brute-force guard)
 *   POST /api/auth/register — 5 requests per minute per IP
 *   GET  /track/open/**     — 30 requests per minute per tracking-ID (inflation guard)
 *
 * Implementation: sliding-window counter keyed on (IP|trackingId) with 60-second reset.
 * This is an in-memory, single-node implementation — suitable for MVP.
 * Replace with Redis + Bucket4j for multi-instance deployments.
 */
@Component
public class RateLimitFilter implements Filter {

    private static final Logger log = LoggerFactory.getLogger(RateLimitFilter.class);

    private static final long WINDOW_MS = 60_000L;

    /** Max attempts per window for each protected path pattern */
    private static final int LOGIN_LIMIT    = 10;
    private static final int REGISTER_LIMIT = 5;
    private static final int TRACK_LIMIT    = 30;

    /** (key → [count, windowStartMs]) */
    private final Map<String, long[]> counters = new ConcurrentHashMap<>();

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
            key   = "track:" + trackingId;
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
        long[] slot = counters.computeIfAbsent(key, k -> new long[]{0L, now});

        synchronized (slot) {
            // Reset window if expired
            if (now - slot[1] > WINDOW_MS) {
                slot[0] = 0L;
                slot[1] = now;
            }
            slot[0]++;
            return slot[0] > limit;
        }
    }

    private String getClientIp(HttpServletRequest request) {
        String xff = request.getHeader("X-Forwarded-For");
        if (xff != null && !xff.isBlank()) {
            return xff.split(",")[0].trim();
        }
        return request.getRemoteAddr();
    }
}
