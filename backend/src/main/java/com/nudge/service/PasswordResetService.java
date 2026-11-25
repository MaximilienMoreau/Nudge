package com.nudge.service;

import com.nudge.model.User;
import com.nudge.repository.UserRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.env.Environment;
import org.springframework.core.env.Profiles;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.Base64;
import java.util.HexFormat;
import java.util.concurrent.CompletableFuture;

/** Password reset by a single-use link sent by email. */
@Service
public class PasswordResetService {

    private static final Logger log = LoggerFactory.getLogger(PasswordResetService.class);

    static final Duration VALIDITY = Duration.ofMinutes(30);
    /** A new link is not sent again before this delay. */
    static final Duration COOLDOWN = Duration.ofMinutes(1);

    private final UserRepository users;
    private final PasswordEncoder passwordEncoder;
    private final EmailNotificationService mail;
    private final boolean dev;
    private final String frontendUrl;
    private final SecureRandom random = new SecureRandom();

    public PasswordResetService(UserRepository users, PasswordEncoder passwordEncoder, EmailNotificationService mail,
                                Environment environment, @Value("${app.frontend.url}") String frontendUrl) {
        this.users = users;
        this.passwordEncoder = passwordEncoder;
        this.mail = mail;
        this.dev = environment.acceptsProfiles(Profiles.of("dev"));
        this.frontendUrl = frontendUrl;
    }

    /** Whether a link can reach the user: by email or, in development, through the server log. */
    public boolean isAvailable() {
        return mail.isEnabled() || dev;
    }

    /** Does nothing for unknown addresses; callers must not be able to tell the difference. */
    @Transactional
    public void request(String email) {
        users.findByEmail(email).ifPresent(user -> {
            LocalDateTime now = LocalDateTime.now();
            LocalDateTime expiry = user.getResetTokenExpiresAt();
            if (expiry != null && expiry.isAfter(now.plus(VALIDITY).minus(COOLDOWN))) return;

            byte[] bytes = new byte[32];
            random.nextBytes(bytes);
            String token = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
            user.setResetTokenHash(hash(token));
            user.setResetTokenExpiresAt(now.plus(VALIDITY));
            users.save(user);

            // In the fragment: never sent to servers, proxies or referrers.
            deliver(user.getEmail(), frontendUrl + "/reset.html#token=" + token);
        });
    }

    private void deliver(String email, String link) {
        if (!mail.isEnabled()) {
            log.warn("DEVELOPMENT ONLY, email is disabled. Password reset link: {}", link);
            return;
        }
        // Outside the request: its duration must not reveal whether the account exists.
        CompletableFuture.runAsync(() -> mail.sendPasswordReset(email, link, VALIDITY.toMinutes()))
                .exceptionally(error -> {
                    log.error("Password reset email could not be sent");
                    return null;
                });
    }

    /** Sets the new password and revokes every existing session. */
    @Transactional
    public void reset(String token, String newPassword) {
        User user = users.findByResetTokenHash(hash(token))
                .filter(found -> found.getResetTokenExpiresAt() != null
                        && found.getResetTokenExpiresAt().isAfter(LocalDateTime.now()))
                .orElseThrow(() -> new IllegalArgumentException("This link is invalid or has expired"));
        user.setPasswordHash(passwordEncoder.encode(newPassword));
        user.setTokenVersion(user.getTokenVersion() + 1);
        user.setResetTokenHash(null);
        user.setResetTokenExpiresAt(null);
        users.save(user);
        log.info("Password reset for user id={}", user.getId());
    }

    static String hash(String token) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(token.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
