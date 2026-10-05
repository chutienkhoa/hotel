package com.example.hotel.repository.customer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

import com.example.hotel.service.customer.GuestDocumentService;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.annotation.Transactional;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * Verifies the batched "first passport image per Guest" lookup (the Check-in guest selectors' preview data) against real
 * PostgreSQL: the oldest passport wins, and a Guest without one is absent.
 */
@SpringBootTest
@Testcontainers(disabledWithoutDocker = true)
class GuestDocumentFirstPassportIntegrationTest {

    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");

    @Autowired
    private GuestDocumentService guestDocumentService;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    /** Supplies Testcontainers database connection properties. */
    @DynamicPropertySource
    static void database(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", postgres::getJdbcUrl);
        registry.add("spring.datasource.username", postgres::getUsername);
        registry.add("spring.datasource.password", postgres::getPassword);
    }

    /** Confirms each Guest maps to its oldest passport document, and a Guest with none is not in the result. */
    @Test
    @Transactional
    void shouldMapEachGuestToItsOldestPassportDocument() {
        UUID userId = insertUser();
        UUID twoPassports = insertGuest(userId, "G-FP-1");
        UUID onePassport = insertGuest(userId, "G-FP-2");
        UUID none = insertGuest(userId, "G-FP-3");
        Instant base = Instant.parse("2026-01-01T00:00:00Z");
        UUID older = insertPassport(userId, twoPassports, base);
        insertPassport(userId, twoPassports, base.plusSeconds(60));
        UUID only = insertPassport(userId, onePassport, base.plusSeconds(30));

        Map<UUID, UUID> result = guestDocumentService.firstPassportDocumentIds();

        assertEquals(older, result.get(twoPassports));
        assertEquals(only, result.get(onePassport));
        assertFalse(result.containsKey(none));
    }

    private UUID insertUser() {
        UUID userId = UUID.randomUUID();
        jdbcTemplate.update(
                "INSERT INTO app_user (id, username, password_hash, active, created_at, updated_at) "
                        + "VALUES (?, ?, ?, TRUE, ?, ?)",
                userId, "first-passport-" + userId, "not-used-in-test",
                Timestamp.from(Instant.now()), Timestamp.from(Instant.now()));
        return userId;
    }

    private UUID insertGuest(UUID userId, String guestCode) {
        UUID guestId = UUID.randomUUID();
        jdbcTemplate.update(
                "INSERT INTO guest (id, guest_code, first_name, last_name, created_at, created_by, updated_at, updated_by) "
                        + "VALUES (?, ?, ?, ?, ?, ?, ?, ?)",
                guestId, guestCode, "Test", "Guest", Timestamp.from(Instant.now()), userId,
                Timestamp.from(Instant.now()), userId);
        return guestId;
    }

    private UUID insertPassport(UUID userId, UUID guestId, Instant createdAt) {
        UUID documentId = UUID.randomUUID();
        jdbcTemplate.update(
                "INSERT INTO guest_document (id, guest_id, document_type, original_name, content_type, file_size, "
                        + "storage_key, created_at, created_by, updated_at, updated_by) "
                        + "VALUES (?, ?, 'PASSPORT_IMAGE', 'p.jpg', 'image/jpeg', 10, ?, ?, ?, ?, ?)",
                documentId, guestId, "key-" + documentId, Timestamp.from(createdAt), userId,
                Timestamp.from(createdAt), userId);
        return documentId;
    }
}
