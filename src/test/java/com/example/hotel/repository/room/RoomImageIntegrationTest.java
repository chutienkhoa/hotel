package com.example.hotel.repository.room;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.example.hotel.dto.room.response.RoomImageResponse;
import com.example.hotel.security.CurrentUser;
import com.example.hotel.service.room.RoomImageService;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import javax.imageio.ImageIO;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * Verifies Room Images V1 against real PostgreSQL: the database partial-unique-index primary
 * invariant, the end-to-end upload/set-primary/remove flow, a historical Room with zero images,
 * and concurrent upload/set-primary races.
 */
@SpringBootTest
@Testcontainers(disabledWithoutDocker = true)
class RoomImageIntegrationTest {

    private static final UUID SINGLE_ROOM_TYPE_ID = UUID.fromString("00000000-0000-0000-0000-000000000201");

    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");

    @Autowired
    private RoomImageService roomImageService;

    @Autowired
    private JdbcTemplate jdbc;

    private UUID user;
    private UUID roomId;

    /** Supplies the Testcontainers PostgreSQL connection to Spring. */
    @DynamicPropertySource
    static void database(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", postgres::getJdbcUrl);
        registry.add("spring.datasource.username", postgres::getUsername);
        registry.add("spring.datasource.password", postgres::getPassword);
    }

    /** Clears Room image fixtures and creates a fresh acting user and Room. */
    @BeforeEach
    void setUp() {
        jdbc.update("DELETE FROM room_image");
        jdbc.update("DELETE FROM room WHERE room_number LIKE 'RI-%'");
        user = UUID.randomUUID();
        jdbc.update(
                "INSERT INTO app_user (id, username, password_hash, active, created_at, updated_at) "
                        + "VALUES (?, ?, 'hash', TRUE, now(), now())",
                user, "riuser." + user);
        roomId = UUID.randomUUID();
        jdbc.update(
                "INSERT INTO room (id, room_number, room_type_id, floor, status, active, "
                        + "created_at, created_by, updated_at, updated_by) "
                        + "VALUES (?, ?, ?, '1', 'AVAILABLE', TRUE, now(), ?, now(), ?)",
                roomId, "RI-" + UUID.randomUUID().toString().substring(0, 8), SINGLE_ROOM_TYPE_ID, user, user);
        authenticate();
    }

    /** Confirms the database partial unique index rejects a second primary image for the same Room. */
    @Test
    void shouldEnforceAtMostOnePrimaryPerRoomAtTheDatabaseLevel() {
        insertImageRow(true);

        assertThrows(DataIntegrityViolationException.class, () -> insertImageRow(true));
    }

    /** Confirms upload, set-primary, and remove-the-primary (with promotion) work together against real Postgres. */
    @Test
    void shouldUploadSetPrimaryAndRemoveEndToEnd() {
        roomImageService.addImages(roomId, List.of(jpeg("a.jpg")));
        roomImageService.addImages(roomId, List.of(jpeg("b.jpg")));

        List<RoomImageResponse> images = roomImageService.findByRoomId(roomId);
        assertEquals(2, images.size());
        assertTrue(images.get(0).primary());
        assertEquals(1, count("SELECT COUNT(*) FROM room_image WHERE room_id = ? AND is_primary", roomId));

        roomImageService.setPrimary(roomId, images.get(1).id());
        assertEquals(1, count("SELECT COUNT(*) FROM room_image WHERE room_id = ? AND is_primary", roomId));
        assertTrue(roomImageService.findByRoomId(roomId).stream()
                .filter(image -> image.id().equals(images.get(1).id()))
                .findFirst()
                .orElseThrow()
                .primary());

        roomImageService.removeImage(roomId, images.get(1).id());
        List<RoomImageResponse> remaining = roomImageService.findByRoomId(roomId);
        assertEquals(1, remaining.size());
        assertTrue(remaining.get(0).primary(), "deleting the primary image promotes the oldest remaining image");
    }

    /** Confirms a Room predating this feature (zero images, never touched) behaves normally. */
    @Test
    void shouldHaveZeroImagesForAnExistingHistoricalRoom() {
        assertTrue(roomImageService.findByRoomId(roomId).isEmpty());
    }

    /** Confirms concurrent uploads to the same Room never leave more than one primary image. */
    @Test
    void shouldSerializeConcurrentUploadsSoExactlyOnePrimaryRemains() throws Exception {
        race(
                () -> roomImageService.addImages(roomId, List.of(jpeg("race-a.jpg"))),
                () -> roomImageService.addImages(roomId, List.of(jpeg("race-b.jpg"))));

        assertEquals(2, count("SELECT COUNT(*) FROM room_image WHERE room_id = ?", roomId));
        assertEquals(1, count("SELECT COUNT(*) FROM room_image WHERE room_id = ? AND is_primary", roomId));
    }

    /** Confirms two concurrent set-primary requests for different images never leave two primaries. */
    @Test
    void shouldSerializeConcurrentSetPrimaryRequests() throws Exception {
        roomImageService.addImages(roomId, List.of(jpeg("a.jpg"), jpeg("b.jpg")));
        List<RoomImageResponse> images = roomImageService.findByRoomId(roomId);
        UUID first = images.get(0).id();
        UUID second = images.get(1).id();

        race(() -> roomImageService.setPrimary(roomId, first), () -> roomImageService.setPrimary(roomId, second));

        assertEquals(1, count("SELECT COUNT(*) FROM room_image WHERE room_id = ? AND is_primary", roomId));
    }

    private void authenticate() {
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(new CurrentUser(user, "manager"), null, List.of()));
    }

    private void insertImageRow(boolean primary) {
        jdbc.update(
                "INSERT INTO room_image (id, room_id, storage_key, original_filename, content_type, file_size, "
                        + "is_primary, created_at, created_by, updated_at, updated_by) "
                        + "VALUES (?, ?, ?, 'x.jpg', 'image/jpeg', 100, ?, ?, ?, ?, ?)",
                UUID.randomUUID(), roomId, UUID.randomUUID() + ".jpg", primary, Timestamp.from(Instant.now()), user,
                Timestamp.from(Instant.now()), user);
    }

    private int count(String sql, Object... args) {
        return jdbc.queryForObject(sql, Integer.class, args);
    }

    private MockMultipartFile jpeg(String filename) {
        try {
            BufferedImage image = new BufferedImage(4, 4, BufferedImage.TYPE_INT_RGB);
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            ImageIO.write(image, "jpg", out);
            return new MockMultipartFile("images", filename, "image/jpeg", out.toByteArray());
        } catch (IOException exception) {
            throw new IllegalStateException(exception);
        }
    }

    /** Races two operations on separate threads with their own authentication, returning each outcome. */
    private List<Object> race(Runnable first, Runnable second) throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(2);
        CyclicBarrier barrier = new CyclicBarrier(2);
        List<Future<Object>> futures = new ArrayList<>();
        for (Runnable task : List.of(first, second)) {
            Callable<Object> call = () -> {
                authenticate();
                barrier.await();
                try {
                    task.run();
                    return "ok";
                } catch (RuntimeException exception) {
                    return exception;
                }
            };
            futures.add(pool.submit(call));
        }
        List<Object> results = new ArrayList<>();
        for (Future<Object> future : futures) {
            results.add(future.get());
        }
        pool.shutdown();
        return results;
    }
}
