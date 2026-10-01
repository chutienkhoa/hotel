package com.example.hotel.repository.booking;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.example.hotel.dto.booking.request.CreateRequest;
import com.example.hotel.dto.booking.request.RoomRequest;
import com.example.hotel.dto.booking.response.Response;
import com.example.hotel.entity.booking.BookingSource;
import com.example.hotel.security.CurrentUser;
import com.example.hotel.service.booking.ReservationService;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.web.server.ResponseStatusException;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * Verifies against PostgreSQL that the entity mapping matches the V30 columns and that create, draft edit and
 * confirmation persist and protect the guest composition; a party larger than room capacity stays valid.
 */
@SpringBootTest
@Testcontainers(disabledWithoutDocker = true)
class ReservationGuestCompositionPersistenceIntegrationTest {

    private static final UUID DOUBLE_ROOM_TYPE_ID = UUID.fromString("00000000-0000-0000-0000-000000000202");

    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");

    @Autowired
    private ReservationService service;

    @Autowired
    private JdbcTemplate jdbc;

    private UUID user;
    private UUID guest;
    private UUID room;

    /**
     * Supplies the Testcontainers PostgreSQL connection to Spring.
     *
     * @param registry dynamic property registry
     */
    @DynamicPropertySource
    static void database(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", postgres::getJdbcUrl);
        registry.add("spring.datasource.username", postgres::getUsername);
        registry.add("spring.datasource.password", postgres::getPassword);
    }

    /** Creates the acting user, a guest and a capacity-2 (Double) room. */
    @BeforeEach
    void setUp() {
        user = UUID.randomUUID();
        jdbc.update("INSERT INTO app_user (id, username, password_hash, active, created_at, updated_at) "
                + "VALUES (?, ?, 'hash', TRUE, now(), now())", user, "gc." + user);
        guest = UUID.randomUUID();
        jdbc.update("INSERT INTO guest (id, guest_code, first_name, last_name, created_at, created_by, updated_at, updated_by) "
                + "VALUES (?, ?, 'Ann', 'Lee', now(), ?, now(), ?)", guest, "G" + guest.toString().substring(0, 8), user, user);
        room = UUID.randomUUID();
        jdbc.update("INSERT INTO room (id, room_number, room_type_id, status, active, created_at, created_by, updated_at, updated_by) "
                + "VALUES (?, ?, ?, 'AVAILABLE', TRUE, now(), ?, now(), ?)", room, "GC" + room.toString().substring(0, 6),
                DOUBLE_ROOM_TYPE_ID, user, user);
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(new CurrentUser(user, "manager"), null, List.of()));
    }

    /** Confirms create persists the counts (over room capacity allowed), draft edit changes them, confirm freezes them. */
    @Test
    void shouldPersistEditAndFreezeGuestComposition() {
        LocalDate in = LocalDate.now().plusDays(30);
        Response created = service.create(request(in, 3, 1));

        assertEquals(counts(3, 1), row(created.id()));

        service.updateDraft(created.id(), request(in, 4, 0));
        assertEquals(counts(4, 0), row(created.id()));

        // Capacity is enforced at Confirm: bring the adults within the room's capacity (2), then confirm.
        service.updateDraft(created.id(), request(in, 2, 1));
        service.confirm(created.id());
        assertThrows(ResponseStatusException.class, () -> service.updateDraft(created.id(), request(in, 1, 0)));
        assertEquals(counts(2, 1), row(created.id()));
    }

    /** Confirms invalid counts are rejected and leave nothing behind. */
    @Test
    void shouldRejectInvalidCounts() {
        LocalDate in = LocalDate.now().plusDays(40);
        int before = jdbc.queryForObject("SELECT COUNT(*) FROM reservation", Integer.class);

        assertThrows(ResponseStatusException.class, () -> service.create(request(in, 0, 0)));
        assertThrows(ResponseStatusException.class, () -> service.create(request(in, 1, -1)));

        assertEquals(before, jdbc.queryForObject("SELECT COUNT(*) FROM reservation", Integer.class));
    }

    private CreateRequest request(LocalDate in, int adults, int children) {
        return new CreateRequest(guest, in, in.plusDays(2), adults, children, BookingSource.DIRECT, null, "VND", null,
                List.of(new RoomRequest(room, new BigDecimal("1000000"))), List.of());
    }

    private Map<String, Object> row(UUID id) {
        return jdbc.queryForMap("SELECT adult_count, child_count FROM reservation WHERE id = ?", id);
    }

    private Map<String, Object> counts(int adults, int children) {
        return Map.of("adult_count", adults, "child_count", children);
    }
}
