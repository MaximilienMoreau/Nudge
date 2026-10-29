package com.nudge.security;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.net.URI;
import java.util.Arrays;
import java.util.List;

/** Protect cookie-authenticated writes, including when SameSite=None is configured. */
public class CookieOriginFilter extends OncePerRequestFilter {
    private final CorsConfiguration origins = new CorsConfiguration();

    public CookieOriginFilter(String allowedOrigins) {
        origins.setAllowedOriginPatterns(Arrays.stream(allowedOrigins.split(","))
                .map(String::trim).filter(s -> !s.isEmpty()).toList());
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain chain) throws ServletException, IOException {
        if (!request.getRequestURI().startsWith("/api/") ||
                List.of("GET", "HEAD", "OPTIONS").contains(request.getMethod())) {
            chain.doFilter(request, response);
            return;
        }
        String authorization = request.getHeader("Authorization");
        if (authorization != null && authorization.startsWith("Bearer ")) {
            chain.doFilter(request, response);
            return;
        }
        boolean hasCookie = request.getCookies() != null && Arrays.stream(request.getCookies())
                .anyMatch(cookie -> SecurityConstants.JWT_COOKIE_NAME.equals(cookie.getName()));
        String origin = request.getHeader("Origin");
        if (origin == null && hasCookie) {
            try {
                URI referer = URI.create(request.getHeader("Referer"));
                origin = referer.getScheme() + "://" + referer.getRawAuthority();
            } catch (RuntimeException ignored) { /* missing or malformed Referer fails closed */ }
        }
        if ((origin != null && origins.checkOrigin(origin) == null) || (hasCookie && origin == null)) {
            response.setStatus(HttpServletResponse.SC_FORBIDDEN);
            response.setContentType("application/json");
            response.getWriter().write("{\"error\":\"Untrusted request origin\"}");
            return;
        }
        chain.doFilter(request, response);
    }
}
