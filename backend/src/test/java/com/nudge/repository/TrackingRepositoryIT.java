package com.nudge.repository;

import com.nudge.model.EventType;
import com.nudge.model.TrackedEmail;
import com.nudge.model.TrackingEvent;
import com.nudge.model.User;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.flyway.FlywayAutoConfiguration;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.boot.autoconfigure.ImportAutoConfiguration;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Integration tests for the repository layer — validates JPA queries against a real
 * PostgreSQL database (Docker container via Testcontainers) running the full Flyway schema.
 *
 * Why integration tests here?
 *   Spring Data JPA query methods look deceptively simple but are translated to SQL
 *   by Hibernate. Mistakes like wrong column names, missing indexes, or JPQL
 *   syntax errors only surface against a real database — not with H2 or mocks.
 *   The native SQL queries (findBestSendSlot) are especially fragile.
 *
 * Each @Test method runs in its own transaction that is rolled back after the method,
 * so tests are fully isolated regardless of execution order.
 *
 * To add a Redis-backed rate limiter later, add a RedisContainer here and replicate
 * this pattern for a RateLimitServiceIT.
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@ImportAutoConfiguration(FlywayAutoConfiguration.class)
@Testcontainers(disabledWithoutDocker = true)
class TrackingRepositoryIT {

    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> postgres =
            new PostgreSQLContainer<>("postgres:16-alpine");

    /** Let Flyway (not Hibernate DDL) own the schema in this test context. */
    @DynamicPropertySource
    static void flyway(DynamicPropertyRegistry registry) {
        registry.add("spring.flyway.enabled",              () -> "true");
        registry.add("spring.flyway.baseline-on-migrate",  () -> "true");
        registry.add("spring.flyway.locations",            () -> "classpath:db/migration");
        registry.add("spring.jpa.hibernate.ddl-auto",      () -> "none");
    }

    @Autowired TrackedEmailRepository emailRepo;
    @Autowired TrackingEventRepository eventRepo;
    @Autowired UserRepository          userRepo;

    private User         user;
    private TrackedEmail email;

    @BeforeEach
    void setUp() {
        user = new User();
        user.setEmail("owner@example.com");
        user.setPasswordHash("$2a$12$hashedPassword");
        userRepo.save(user);

        email = new TrackedEmail();
        email.setUser(user);
        email.setSubject("Test email subject");
        email.setTrackingId("abc-123-tracking-uuid");
        email.setRecipientEmail("recipient@example.com");
        emailRepo.save(email);
    }

    // ── TrackedEmailRepository ───────────────────────────────────────────────

    @Test
    void findByTrackingId_returnsEmail_whenExists() {
        Optional<TrackedEmail> result = emailRepo.findByTrackingId("abc-123-tracking-uuid");

        assertThat(result).isPresent();
        assertThat(result.get().getSubject()).isEqualTo("Test email subject");
        assertThat(result.get().getUser().getId()).isEqualTo(user.getId());
    }

    @Test
    void findByTrackingId_returnsEmpty_whenNotFound() {
        Optional<TrackedEmail> result = emailRepo.findByTrackingId("does-not-exist");

        assertThat(result).isEmpty();
    }

    @Test
    void findByUserAndArchivedAtIsNull_excludesArchivedEmails() {
        // Archive the first email
        email.setArchivedAt(java.time.LocalDateTime.now());
        emailRepo.save(email);

        // Create a second, active email
        TrackedEmail active = new TrackedEmail();
        active.setUser(user);
        active.setSubject("Active email");
        active.setTrackingId("active-uuid-456");
        active.setRecipientEmail("other@example.com");
        emailRepo.save(active);

        var page = emailRepo.findByUserAndArchivedAtIsNullOrderByCreatedAtDesc(
                user, org.springframework.data.domain.PageRequest.of(0, 50));

        assertThat(page.getContent()).hasSize(1);
        assertThat(page.getContent().get(0).getTrackingId()).isEqualTo("active-uuid-456");
    }

    @Test
    void findByUserAndArchivedAtIsNotNull_returnsOnlyArchived() {
        email.setArchivedAt(java.time.LocalDateTime.now());
        emailRepo.save(email);

        var archived = emailRepo
                .findByUserAndArchivedAtIsNotNullOrderByArchivedAtDesc(
                        user, org.springframework.data.domain.Pageable.unpaged());

        assertThat(archived.getContent()).hasSize(1);
        assertThat(archived.getContent().get(0).getId()).isEqualTo(email.getId());
    }

    // ── TrackingEventRepository ──────────────────────────────────────────────

    @Test
    void countByEmailAndTypeAndSuspectedBotFalse_excludesBotEvents() {
        saveEvent(EventType.OPEN, false);
        saveEvent(EventType.OPEN, false);
        saveEvent(EventType.OPEN, true);  // bot — must be excluded

        long count = eventRepo.countByEmailAndTypeAndSuspectedBotFalse(email, EventType.OPEN);

        assertThat(count).isEqualTo(2);
    }

    @Test
    void countByEmailAndTypeAndSuspectedBotFalse_countsOnlySpecifiedType() {
        saveEvent(EventType.OPEN,  false);
        saveEvent(EventType.CLICK, false);

        long openCount  = eventRepo.countByEmailAndTypeAndSuspectedBotFalse(email, EventType.OPEN);
        long clickCount = eventRepo.countByEmailAndTypeAndSuspectedBotFalse(email, EventType.CLICK);

        assertThat(openCount).isEqualTo(1);
        assertThat(clickCount).isEqualTo(1);
    }

    @Test
    void countByEmailAndType_includesBotEvents() {
        saveEvent(EventType.OPEN, false);
        saveEvent(EventType.OPEN, true);

        long total = eventRepo.countByEmailAndType(email, EventType.OPEN);

        assertThat(total).isEqualTo(2);
    }

    @Test
    void findFirstByEmailAndTypeAndSuspectedBotFalse_returnsEvent_whenGenuineEventExists() {
        saveEvent(EventType.OPEN, true);   // bot — should be ignored
        saveEvent(EventType.OPEN, false);  // genuine — should be returned

        Optional<TrackingEvent> result = eventRepo
                .findFirstByEmailAndTypeAndSuspectedBotFalseOrderByTimestampDesc(email, EventType.OPEN);

        assertThat(result).isPresent();
        assertThat(result.get().isSuspectedBot()).isFalse();
    }

    @Test
    void findFirstByEmailAndTypeAndSuspectedBotFalse_returnsEmpty_whenOnlyBotEvents() {
        saveEvent(EventType.OPEN, true);
        saveEvent(EventType.OPEN, true);

        Optional<TrackingEvent> result = eventRepo
                .findFirstByEmailAndTypeAndSuspectedBotFalseOrderByTimestampDesc(email, EventType.OPEN);

        assertThat(result).isEmpty();
    }

    @Test
    void findFirstByEmailAndTypeAndSuspectedBotFalse_returnsEmpty_whenNoEvents() {
        Optional<TrackingEvent> result = eventRepo
                .findFirstByEmailAndTypeAndSuspectedBotFalseOrderByTimestampDesc(email, EventType.OPEN);

        assertThat(result).isEmpty();
    }

    // ── Native SQL queries ───────────────────────────────────────────────────

    @Test
    void findBestSendSlot_returnsRow_whenOpenEventsExist() {
        saveEvent(EventType.OPEN, false);
        saveEvent(EventType.OPEN, false);

        List<Object[]> slots = eventRepo.findBestSendSlot(user.getEmail());

        assertThat(slots).hasSize(1);
        Object[] row = slots.get(0);
        assertThat(row).hasSize(3);
        // col[0] = day of week (1-7), col[1] = hour (0-23), col[2] = count
        int dayOfWeek = ((Number) row[0]).intValue();
        int hour      = ((Number) row[1]).intValue();
        long count    = ((Number) row[2]).longValue();
        assertThat(dayOfWeek).isBetween(1, 7);
        assertThat(hour).isBetween(0, 23);
        assertThat(count).isGreaterThanOrEqualTo(1);
    }

    @Test
    void findBestSendSlot_returnsEmpty_whenNoOpenEvents() {
        List<Object[]> slots = eventRepo.findBestSendSlot(user.getEmail());

        assertThat(slots).isEmpty();
    }

    @Test
    void countOpensByUserEmail_returnsCorrectCount() {
        saveEvent(EventType.OPEN, false);
        saveEvent(EventType.OPEN, true);   // bot also counts here (used for "enough data?" check)
        saveEvent(EventType.CLICK, false);

        long count = eventRepo.countOpensByUserEmail(user.getEmail());

        assertThat(count).isEqualTo(2);
    }

    // ── UserRepository ───────────────────────────────────────────────────────

    @Test
    void userRepository_existsByEmail_returnsTrue_whenExists() {
        assertThat(userRepo.existsByEmail("owner@example.com")).isTrue();
    }

    @Test
    void userRepository_existsByEmail_returnsFalse_whenNotFound() {
        assertThat(userRepo.existsByEmail("nobody@example.com")).isFalse();
    }

    @Test
    void userRepository_findByEmail_returnsUser_whenExists() {
        Optional<User> found = userRepo.findByEmail("owner@example.com");

        assertThat(found).isPresent();
        assertThat(found.get().getEmail()).isEqualTo("owner@example.com");
    }

    // ── Helpers ──────────────────────────────────────────────────────────────

    private TrackingEvent saveEvent(EventType type, boolean isBot) {
        TrackingEvent event = new TrackingEvent();
        event.setEmail(email);
        event.setType(type);
        event.setSuspectedBot(isBot);
        return eventRepo.save(event);
    }
}
