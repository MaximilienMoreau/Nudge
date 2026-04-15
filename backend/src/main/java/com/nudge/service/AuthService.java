package com.nudge.service;

import com.nudge.dto.AuthResponse;
import com.nudge.dto.LoginRequest;
import com.nudge.dto.PasswordChangeRequest;
import com.nudge.dto.RegisterRequest;
import com.nudge.model.User;
import com.nudge.repository.UserRepository;
import com.nudge.security.JwtUtil;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Handles user registration, login, logout, and password change.
 *
 * S6: tokenVersion is incremented on password change and logout so that all
 *     previously issued JWTs are immediately rejected by JwtAuthFilter.
 *
 * F7: changePassword validates the current password before accepting a new one.
 */
@Service
public class AuthService {

    private static final Logger log = LoggerFactory.getLogger(AuthService.class);

    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;
    private final JwtUtil jwtUtil;
    private final AuthenticationManager authManager;

    public AuthService(UserRepository userRepository,
                       PasswordEncoder passwordEncoder,
                       JwtUtil jwtUtil,
                       AuthenticationManager authManager) {
        this.userRepository = userRepository;
        this.passwordEncoder = passwordEncoder;
        this.jwtUtil = jwtUtil;
        this.authManager = authManager;
    }

    /** Register a new user. Throws if email already exists. */
    @Transactional
    public AuthResponse register(RegisterRequest request) {
        if (userRepository.existsByEmail(request.getEmail())) {
            throw new IllegalArgumentException("Email already in use: " + request.getEmail());
        }

        User user = new User();
        user.setEmail(request.getEmail());
        user.setPasswordHash(passwordEncoder.encode(request.getPassword()));
        userRepository.save(user);

        log.info("New user registered: {}", user.getEmail());
        String token = jwtUtil.generateToken(user.getId(), user.getEmail(), user.getTokenVersion());
        return new AuthResponse(token, user.getEmail(), user.getId());
    }

    /** Authenticate a user and return a JWT. Throws on bad credentials. */
    public AuthResponse login(LoginRequest request) {
        authManager.authenticate(
                new UsernamePasswordAuthenticationToken(request.getEmail(), request.getPassword())
        );

        User user = userRepository.findByEmail(request.getEmail())
                .orElseThrow(() -> new IllegalArgumentException("User not found"));

        String token = jwtUtil.generateToken(user.getId(), user.getEmail(), user.getTokenVersion());
        log.info("User logged in: {}", user.getEmail());
        return new AuthResponse(token, user.getEmail(), user.getId());
    }

    /**
     * F7: Change the authenticated user's password.
     * S6: Increments tokenVersion so all existing tokens are revoked.
     * Returns a new JWT issued with the new tokenVersion.
     */
    @Transactional
    public AuthResponse changePassword(String userEmail, PasswordChangeRequest request) {
        User user = userRepository.findByEmail(userEmail)
                .orElseThrow(() -> new IllegalArgumentException("User not found"));

        if (!passwordEncoder.matches(request.getCurrentPassword(), user.getPasswordHash())) {
            throw new BadCredentialsException("Current password is incorrect");
        }

        user.setPasswordHash(passwordEncoder.encode(request.getNewPassword()));
        user.setTokenVersion(user.getTokenVersion() + 1);  // S6: revoke all existing tokens
        userRepository.save(user);

        log.info("Password changed for user: {}", userEmail);
        String newToken = jwtUtil.generateToken(user.getId(), user.getEmail(), user.getTokenVersion());
        return new AuthResponse(newToken, user.getEmail(), user.getId());
    }

    /**
     * S6: Explicit logout — increments tokenVersion to invalidate the current token.
     * The frontend should discard its stored JWT after calling this.
     */
    @Transactional
    public void logout(String userEmail) {
        User user = userRepository.findByEmail(userEmail)
                .orElseThrow(() -> new IllegalArgumentException("User not found"));
        user.setTokenVersion(user.getTokenVersion() + 1);
        userRepository.save(user);
        log.info("User logged out (token revoked): {}", userEmail);
    }
}
