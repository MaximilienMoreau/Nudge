package com.nudge.controller;

import com.nudge.dto.AuthResponse;
import com.nudge.dto.LoginRequest;
import com.nudge.dto.PasswordChangeRequest;
import com.nudge.dto.RegisterRequest;
import com.nudge.service.AuthService;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseCookie;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.web.bind.annotation.*;

import java.time.Duration;
import java.util.Map;

/**
 * Authentication endpoints.
 *
 * F7: Added PUT /api/auth/password for authenticated password change.
 * S6: Added POST /api/auth/logout to revoke the current token server-side.
 */
@RestController
@RequestMapping("/api/auth")
public class AuthController {

    private final AuthService authService;

    @Value("${jwt.expiration:86400000}")
    private long jwtExpirationMs;

    public AuthController(AuthService authService) {
        this.authService = authService;
    }

    /** POST /api/auth/register — Create a new account (public). */
    @PostMapping("/register")
    public ResponseEntity<AuthResponse> register(@Valid @RequestBody RegisterRequest request,
                                                 HttpServletResponse response) {
        AuthResponse auth = authService.register(request);
        setAuthCookie(response, auth.getToken());
        return ResponseEntity.ok(auth);
    }

    /** POST /api/auth/login — Exchange credentials for a JWT (public). */
    @PostMapping("/login")
    public ResponseEntity<AuthResponse> login(@Valid @RequestBody LoginRequest request,
                                              HttpServletResponse response) {
        AuthResponse auth = authService.login(request);
        setAuthCookie(response, auth.getToken());
        return ResponseEntity.ok(auth);
    }

    /**
     * F7: PUT /api/auth/password — Change the authenticated user's password.
     * Returns a new JWT; the client must replace the stored token.
     */
    @PutMapping("/password")
    public ResponseEntity<AuthResponse> changePassword(
            @AuthenticationPrincipal UserDetails principal,
            @Valid @RequestBody PasswordChangeRequest request) {
        return ResponseEntity.ok(authService.changePassword(principal.getUsername(), request));
    }

    /**
     * S6: POST /api/auth/logout — Invalidate the current JWT server-side by
     * incrementing the user's tokenVersion.
     */
    @PostMapping("/logout")
    public ResponseEntity<Map<String, String>> logout(
            @AuthenticationPrincipal UserDetails principal,
            HttpServletResponse response) {
        authService.logout(principal.getUsername());
        clearAuthCookie(response);
        return ResponseEntity.ok(Map.of("message", "Logged out successfully"));
    }

    private void setAuthCookie(HttpServletResponse response, String token) {
        ResponseCookie cookie = ResponseCookie.from("nudge_jwt", token)
                .httpOnly(true)
                .sameSite("Strict")
                .path("/")
                .maxAge(Duration.ofMillis(jwtExpirationMs))
                .build();
        response.addHeader(HttpHeaders.SET_COOKIE, cookie.toString());
    }

    private void clearAuthCookie(HttpServletResponse response) {
        ResponseCookie cookie = ResponseCookie.from("nudge_jwt", "")
                .httpOnly(true)
                .sameSite("Strict")
                .path("/")
                .maxAge(0)
                .build();
        response.addHeader(HttpHeaders.SET_COOKIE, cookie.toString());
    }
}
