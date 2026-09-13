package com.example.hotel.config;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.UUID;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;

/** Seeds an isolated, deterministic Dashboard demonstration dataset in the dev profile only. */
@Configuration
@Profile("dev")
public class DemoDataSeeder {

    private static final String MARKER_USERNAME = "demo-data-seeder";
    private static final String PREFIX = "DEMO-";
    private static final long RANDOM_SEED = 20260913L;
    private static final ZoneId BUSINESS_ZONE = ZoneId.of("Asia/Ho_Chi_Minh");
    private static final int[] MONTHLY_RESERVATION_COUNTS = {12, 12, 11, 5, 5, 5, 5, 5, 5, 12, 12, 11};
    private static final List<String> ROOM_TYPE_CODES =
            List.of("SINGLE", "DOUBLE", "TWIN", "TRIPLE", "FAMILY");
    private static final List<String> BOOKING_SOURCES =
            List.of("DIRECT", "AGODA", "BOOKING_COM", "AIRBNB");
    private static final List<String> FIRST_NAMES =
            List.of("Linh", "Minh", "An", "Mai", "Thanh", "Hana", "Khoa", "Nhi");
    private static final List<String> LAST_NAMES =
            List.of("Nguyen", "Tran", "Le", "Pham", "Hoang", "Vu", "Bui", "Do");

    /** Creates the profile-gated runner that generates the demo dataset exactly once. */
    @Bean
    ApplicationRunner demoDataRunner(
            JdbcTemplate jdbcTemplate, Clock dashboardClock, TransactionTemplate transactionTemplate) {
        return arguments -> transactionTemplate.executeWithoutResult(status -> seed(jdbcTemplate, dashboardClock));
    }

    /** Inserts only marked demo rows; existing marked data makes the operation a no-op. */
    void seed(JdbcTemplate jdbcTemplate, Clock dashboardClock) {
        Integer markerCount = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM app_user WHERE username = ?", Integer.class, MARKER_USERNAME);
        if (markerCount != null && markerCount > 0) {
            return;
        }

        Instant now = dashboardClock.instant();
        Timestamp nowTimestamp = timestamp(now);
        LocalDate today = LocalDate.now(dashboardClock);
        UUID auditUserId = deterministicId(MARKER_USERNAME);
        insertAuditUser(jdbcTemplate, auditUserId, nowTimestamp);
        Map<String, UUID> roomTypes = roomTypeIds(jdbcTemplate);
        List<UUID> roomIds = insertRooms(jdbcTemplate, roomTypes, auditUserId, nowTimestamp);
        List<UUID> guestIds = insertGuests(jdbcTemplate, auditUserId, nowTimestamp);
        insertReservations(jdbcTemplate, guestIds, roomIds, auditUserId, today, nowTimestamp);
        setOperationalRoomMix(jdbcTemplate, roomIds, auditUserId, nowTimestamp);
    }

    private void insertAuditUser(JdbcTemplate jdbcTemplate, UUID userId, Timestamp now) {
        jdbcTemplate.update(
                "INSERT INTO app_user (id, username, password_hash, active, created_at, updated_at) "
                        + "VALUES (?, ?, ?, TRUE, ?, ?)",
                userId, MARKER_USERNAME, "$2a$10$7EqJtq98hPqEX7fNZaFWoO9WhE11L8W6m8K7B6p3uJ4eCjO8eqfOi", now, now);
    }

    private Map<String, UUID> roomTypeIds(JdbcTemplate jdbcTemplate) {
        return jdbcTemplate.query(
                "SELECT id, code FROM room_type WHERE code IN (?, ?, ?, ?, ?)",
                resultSet -> {
                    Map<String, UUID> values = new java.util.HashMap<>();
                    while (resultSet.next()) {
                        values.put(resultSet.getString("code"), resultSet.getObject("id", UUID.class));
                    }
                    return values;
                },
                ROOM_TYPE_CODES.toArray());
    }

    private List<UUID> insertRooms(
            JdbcTemplate jdbcTemplate, Map<String, UUID> roomTypes, UUID auditUserId, Timestamp now) {
        List<UUID> roomIds = new java.util.ArrayList<>();
        insertRooms(jdbcTemplate, roomIds, roomTypes, auditUserId, now, "SINGLE", 5, 101);
        insertRooms(jdbcTemplate, roomIds, roomTypes, auditUserId, now, "DOUBLE", 6, 201);
        insertRooms(jdbcTemplate, roomIds, roomTypes, auditUserId, now, "TWIN", 4, 301);
        insertRooms(jdbcTemplate, roomIds, roomTypes, auditUserId, now, "TRIPLE", 4, 401);
        insertRooms(jdbcTemplate, roomIds, roomTypes, auditUserId, now, "FAMILY", 3, 501);
        return List.copyOf(roomIds);
    }

    private void insertRooms(
            JdbcTemplate jdbcTemplate, List<UUID> roomIds, Map<String, UUID> roomTypes, UUID auditUserId,
            Timestamp now, String roomType, int count, int firstNumber) {
        UUID roomTypeId = roomTypes.get(roomType);
        if (roomTypeId == null) {
            throw new IllegalStateException("Approved RoomType is missing: " + roomType);
        }
        for (int index = 0; index < count; index++) {
            UUID roomId = deterministicId(PREFIX + "ROOM-" + (firstNumber + index));
            jdbcTemplate.update(
                    "INSERT INTO room (id, room_number, room_type_id, floor, status, active, created_at, "
                            + "created_by, updated_at, updated_by) VALUES (?, ?, ?, ?, 'AVAILABLE', TRUE, ?, ?, ?, ?)",
                    roomId, PREFIX + (firstNumber + index), roomTypeId, String.valueOf(firstNumber / 100),
                    now, auditUserId, now, auditUserId);
            roomIds.add(roomId);
        }
    }

    private List<UUID> insertGuests(JdbcTemplate jdbcTemplate, UUID auditUserId, Timestamp now) {
        Random random = new Random(RANDOM_SEED);
        List<UUID> guestIds = new java.util.ArrayList<>();
        for (int index = 1; index <= 100; index++) {
            UUID guestId = deterministicId(PREFIX + "GUEST-" + index);
            String firstName = FIRST_NAMES.get(random.nextInt(FIRST_NAMES.size()));
            String lastName = LAST_NAMES.get(random.nextInt(LAST_NAMES.size()));
            jdbcTemplate.update(
                    "INSERT INTO guest (id, guest_code, first_name, last_name, email, phone, nationality, "
                            + "date_of_birth, address, created_at, created_by, updated_at, updated_by) "
                            + "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                    guestId, PREFIX + String.format("G%03d", index), firstName, lastName,
                    "demo.guest" + index + "@example.test", "+840900" + String.format("%06d", index),
                    index % 3 == 0 ? "Japanese" : "Vietnamese", LocalDate.of(1970 + index % 30, 1 + index % 12, 1 + index % 27),
                    index + " Demo Street, Ho Chi Minh City", now, auditUserId, now, auditUserId);
            guestIds.add(guestId);
        }
        return List.copyOf(guestIds);
    }

    private void insertReservations(
            JdbcTemplate jdbcTemplate, List<UUID> guestIds, List<UUID> roomIds, UUID auditUserId,
            LocalDate today, Timestamp now) {
        Random random = new Random(RANDOM_SEED);
        Random sourceRandom = new Random(RANDOM_SEED);
        int reservationIndex = 0;
        for (int month = 1; month <= 12; month++) {
            for (int monthIndex = 0; monthIndex < MONTHLY_RESERVATION_COUNTS[month - 1]; monthIndex++) {
                LocalDate checkInDate = plannedCheckInDate(today, month, monthIndex);
                int nights = nightsFor(today, checkInDate, monthIndex, random);
                String status = reservationStatus(today, checkInDate, monthIndex);
                UUID reservationId = deterministicId(PREFIX + "RESERVATION-" + reservationIndex);
                UUID reservationNumber = deterministicId(PREFIX + "RESERVATION-NUMBER-" + reservationIndex);
                UUID roomId = roomIds.get(roomIndex(status, reservationIndex));
                BigDecimal totalAmount = reservationTotal(nights);
                String source = BOOKING_SOURCES.get(sourceRandom.nextInt(BOOKING_SOURCES.size()));
                Instant reservedAt = checkInDate.minusDays(21)
                        .atTime(10, 0)
                        .atZone(BUSINESS_ZONE)
                        .toInstant();
                Timestamp reservedAtTimestamp = timestamp(reservedAt);
                Timestamp updatedAt = "CHECKED_OUT".equals(status)
                        ? timestamp(checkInDate.plusDays(nights).atTime(11, 0).atZone(BUSINESS_ZONE).toInstant())
                        : now;
                jdbcTemplate.update(
                        "INSERT INTO reservation (id, reservation_number, guest_id, source, external_booking_id, "
                                + "status, reserved_at, check_in_date, check_out_date, currency, total_amount, notes, "
                                + "created_at, created_by, updated_at, updated_by) "
                        + "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, 'VND', ?, ?, ?, ?, ?, ?)",
                        reservationId, reservationNumber, guestIds.get(reservationIndex),
                        source, "DIRECT".equals(source) ? null : PREFIX + "OTA-" + reservationIndex, status,
                        reservedAtTimestamp,
                        checkInDate, checkInDate.plusDays(nights), totalAmount,
                        "Synthetic development demonstration reservation", reservedAtTimestamp, auditUserId,
                        updatedAt, auditUserId);
                insertReservationRoom(
                        jdbcTemplate, reservationId, roomId, auditUserId, checkInDate, nights, reservedAtTimestamp,
                        updatedAt);
                if ("CHECKED_IN".equals(status) || "CHECKED_OUT".equals(status)) {
                    insertStay(
                            jdbcTemplate, reservationId, auditUserId, checkInDate, nights, status,
                            reservedAtTimestamp, updatedAt);
                }
                reservationIndex++;
            }
        }
    }

    private LocalDate plannedCheckInDate(LocalDate today, int month, int index) {
        if (month == today.getMonthValue()) {
            if (index < 5) {
                return today;
            }
            if (today.getDayOfMonth() > 1) {
                return today.withDayOfMonth(1);
            }
            return today.plusDays(Math.min(index, today.lengthOfMonth() - 1));
        }
        LocalDate firstDay = LocalDate.of(today.getYear(), month, 1);
        return firstDay.plusDays(Math.min(index * 2, firstDay.lengthOfMonth() - 2));
    }

    private int nightsFor(LocalDate today, LocalDate checkInDate, int monthIndex, Random random) {
        if (checkInDate.getMonthValue() == today.getMonthValue() && monthIndex < 5) {
            return 2;
        }
        if (checkInDate.isBefore(today)) {
            long daysBeforeToday = java.time.temporal.ChronoUnit.DAYS.between(checkInDate, today);
            return (int) Math.max(1, Math.min(1 + random.nextInt(5), daysBeforeToday));
        }
        return 1 + random.nextInt(5);
    }

    private BigDecimal reservationTotal(int nights) {
        return BigDecimal.valueOf(1_100_000L).multiply(BigDecimal.valueOf(nights));
    }

    private String reservationStatus(LocalDate today, LocalDate checkInDate, int monthIndex) {
        if (checkInDate.equals(today) && monthIndex < 5) {
            return "CHECKED_IN";
        }
        if (checkInDate.isBefore(today)) {
            return monthIndex % 7 == 0 ? "CANCELLED" : monthIndex % 9 == 0 ? "NO_SHOW" : "CHECKED_OUT";
        }
        return monthIndex % 8 == 0 ? "DRAFT" : monthIndex % 11 == 0 ? "CANCELLED" : "CONFIRMED";
    }

    private int roomIndex(String status, int reservationIndex) {
        return "CHECKED_IN".equals(status) ? reservationIndex % 5 : 5 + reservationIndex % 17;
    }

    private void insertReservationRoom(
            JdbcTemplate jdbcTemplate, UUID reservationId, UUID roomId, UUID auditUserId,
            LocalDate checkInDate, int nights, Timestamp createdAt, Timestamp updatedAt) {
        BigDecimal rate = BigDecimal.valueOf(1_100_000L);
        jdbcTemplate.update(
                "INSERT INTO reservation_room (id, reservation_id, room_id, check_in_date, check_out_date, "
                        + "nightly_rate, total_amount, created_at, created_by, updated_at, updated_by) "
                        + "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                deterministicId(PREFIX + "RESERVATION-ROOM-" + reservationId), reservationId, roomId,
                checkInDate, checkInDate.plusDays(nights), rate,
                rate.multiply(BigDecimal.valueOf(nights)), createdAt, auditUserId, updatedAt, auditUserId);
    }

    private void insertStay(
            JdbcTemplate jdbcTemplate, UUID reservationId, UUID auditUserId, LocalDate checkInDate,
            int nights, String reservationStatus, Timestamp createdAt, Timestamp updatedAt) {
        Instant checkedInAt = checkInDate.atTime(14, 0).atZone(BUSINESS_ZONE).toInstant();
        Instant checkedOutAt = "CHECKED_OUT".equals(reservationStatus)
                ? checkInDate.plusDays(nights).atTime(11, 0).atZone(BUSINESS_ZONE).toInstant() : null;
        jdbcTemplate.update(
                "INSERT INTO stay (id, reservation_id, status, actual_check_in_at, actual_check_out_at, "
                        + "created_at, created_by, updated_at, updated_by) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)",
                deterministicId(PREFIX + "STAY-" + reservationId), reservationId, reservationStatus,
                timestamp(checkedInAt), timestamp(checkedOutAt),
                createdAt, auditUserId, updatedAt, auditUserId);
    }

    private void setOperationalRoomMix(
            JdbcTemplate jdbcTemplate, List<UUID> roomIds, UUID auditUserId, Timestamp now) {
        jdbcTemplate.update("UPDATE room SET status = 'OCCUPIED', updated_at = ?, updated_by = ? "
                        + "WHERE id IN (?, ?, ?, ?, ?)", now, auditUserId,
                roomIds.get(0), roomIds.get(1), roomIds.get(2), roomIds.get(3), roomIds.get(4));
        for (int index = 0; index < 4; index++) {
            String status = List.of("DIRTY", "CLEANING", "MAINTENANCE", "OUT_OF_ORDER").get(index);
            jdbcTemplate.update("UPDATE room SET status = ?, updated_at = ?, updated_by = ? WHERE id = ?",
                    status, now, auditUserId, roomIds.get(5 + index));
        }
    }

    private UUID deterministicId(String value) {
        return UUID.nameUUIDFromBytes(value.getBytes(StandardCharsets.UTF_8));
    }

    /**
     * Converts an instant to the JDBC timestamp representation expected by PostgreSQL.
     *
     * @param instant instant to bind, or {@code null} for a nullable timestamp column
     * @return a timestamp representing the same instant, or {@code null}
     */
    private Timestamp timestamp(Instant instant) {
        return instant == null ? null : Timestamp.from(instant);
    }
}
