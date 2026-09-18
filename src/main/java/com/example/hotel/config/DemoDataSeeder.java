package com.example.hotel.config;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.Month;
import java.time.ZoneId;
import java.time.format.TextStyle;
import java.util.List;
import java.util.Locale;
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
    private static final List<String> EXPENSE_CATEGORY_CODES = List.of(
            "ELECTRICITY", "WATER", "INTERNET", "SALARY", "LAUNDRY", "CLEANING", "SUPPLIES",
            "MAINTENANCE", "OTHER", "REPAIR", "CONSTRUCTION", "OTA_COMMISSION");
    private static final List<String> OCCASIONAL_EXPENSE_CATEGORIES = List.of(
            "MAINTENANCE", "REPAIR", "CONSTRUCTION", "SUPPLIES", "LAUNDRY", "CLEANING", "OTA_COMMISSION", "OTHER");
    private static final int OCCASIONAL_EXPENSES_PER_MONTH = 4;
    private static final Map<String, List<String>> OCCASIONAL_EXPENSE_DESCRIPTIONS = Map.of(
            "MAINTENANCE", List.of("Air conditioner maintenance", "Plumbing maintenance", "Electrical maintenance"),
            "REPAIR", List.of("Room equipment repair", "Bathroom fixture repair", "Furniture repair"),
            "CONSTRUCTION", List.of("Small renovation work", "Painting", "Minor property improvement"),
            "SUPPLIES", List.of("Guest amenities purchase", "Cleaning supplies purchase", "Toiletries purchase"),
            "LAUNDRY", List.of("Linen laundry service", "Guest laundry service"),
            "CLEANING", List.of("Housekeeping cleaning service", "Deep cleaning service"),
            "OTA_COMMISSION", List.of("Agoda commission", "Booking.com commission", "Airbnb commission"),
            "OTHER", List.of("Miscellaneous hotel expense"));
    private static final List<String> HISTORICAL_STATUS_CYCLE = List.of(
            "POSTED", "POSTED", "POSTED", "POSTED", "POSTED", "POSTED", "POSTED", "POSTED",
            "POSTED", "POSTED", "POSTED", "POSTED", "POSTED", "POSTED", "POSTED", "POSTED",
            "APPROVED", "APPROVED", "SUBMITTED", "DRAFT");
    private static final List<String> RECENT_STATUS_CYCLE =
            List.of("DRAFT", "SUBMITTED", "APPROVED", "POSTED");
    private static final int REJECTED_EVERY_NTH_HISTORICAL_EXPENSE = 25;
    private static final List<String> ADDITIONAL_REVENUE_CATEGORY_CODES =
            List.of("ELECTRIC_CART_RENTAL", "OTHER");
    private static final List<Integer> OTHER_ADDITIONAL_REVENUE_INDICES =
            List.of(1, 6, 10, 15, 20, 24, 29, 34);
    private static final List<Integer> VOIDED_ADDITIONAL_REVENUE_INDICES = List.of(2, 11, 20, 31);
    private static final List<Integer> BANK_TRANSFER_ADDITIONAL_REVENUE_INDICES =
            List.of(4, 9, 14, 19, 24, 29, 34);
    private static final List<Integer> CREDIT_CARD_ADDITIONAL_REVENUE_INDICES = List.of(7, 18, 30);
    private static final List<Integer> OTHER_PAYMENT_ADDITIONAL_REVENUE_INDICES = List.of(12, 26);
    private static final List<String> ADDITIONAL_REVENUE_VOID_REASONS =
            List.of("Duplicate entry", "Incorrect amount", "Entered by mistake", "Wrong category");
    private static final List<String> STAFF_POSITIONS = List.of(
            "Manager", "Receptionist", "Housekeeping", "Maintenance", "Security", "Accountant",
            "Night Reception", "Receptionist", "Housekeeping", "Part-time");
    private static final int STAFF_COUNT = 10;
    private static final int INACTIVE_STAFF_COUNT = 2;
    private static final int ACTIVE_STAFF_WORK_RECORD_DAYS = 5;

    /** Creates the profile-gated runner that generates the demo dataset exactly once. */
    @Bean
    ApplicationRunner demoDataRunner(
            JdbcTemplate jdbcTemplate, Clock dashboardClock, TransactionTemplate transactionTemplate) {
        return arguments -> transactionTemplate.executeWithoutResult(status -> seed(jdbcTemplate, dashboardClock));
    }

    /** Inserts marked demo rows and safely backfills a newly introduced demo data slice once. */
    void seed(JdbcTemplate jdbcTemplate, Clock dashboardClock) {
        Integer markerCount = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM app_user WHERE username = ?", Integer.class, MARKER_USERNAME);
        if (markerCount != null && markerCount > 0) {
            UUID existingAuditUserId = deterministicId(MARKER_USERNAME);
            LocalDate existingToday = LocalDate.now(dashboardClock);
            insertAdditionalRevenues(jdbcTemplate, existingAuditUserId, existingToday);
            List<UUID> existingStaffIds =
                    insertStaff(jdbcTemplate, existingAuditUserId, existingToday, timestamp(dashboardClock.instant()));
            insertDailyWorkRecords(jdbcTemplate, existingStaffIds, existingAuditUserId, existingToday);
            return;
        }

        Instant now = dashboardClock.instant();
        Timestamp nowTimestamp = timestamp(now);
        LocalDate today = LocalDate.now(dashboardClock);
        UUID auditUserId = deterministicId(MARKER_USERNAME);
        insertAuditUser(jdbcTemplate, auditUserId, nowTimestamp);
        Map<String, UUID> roomTypes = roomTypeIds(jdbcTemplate);
        Map<UUID, String> roomNumbers = new java.util.LinkedHashMap<>();
        List<UUID> roomIds = insertRooms(jdbcTemplate, roomTypes, roomNumbers, auditUserId, nowTimestamp);
        List<UUID> guestIds = insertGuests(jdbcTemplate, auditUserId, nowTimestamp);
        insertReservations(jdbcTemplate, guestIds, roomIds, roomNumbers, auditUserId, today, nowTimestamp);
        setOperationalRoomMix(jdbcTemplate, roomIds, auditUserId, nowTimestamp);
        insertExpenses(jdbcTemplate, auditUserId, today);
        insertAdditionalRevenues(jdbcTemplate, auditUserId, today);
        List<UUID> staffIds = insertStaff(jdbcTemplate, auditUserId, today, nowTimestamp);
        insertDailyWorkRecords(jdbcTemplate, staffIds, auditUserId, today);
    }

    /**
     * Inserts a fixed, deterministic set of demo Staff members (mostly active, a small number
     * inactive), or returns their already-existing identifiers on a subsequent application start.
     *
     * @param jdbcTemplate JDBC access used to insert demo rows
     * @param auditUserId demo marker user recorded as the creator/updater
     * @param today current hotel business date, used to derive a plausible Start Date
     * @param now timestamp recorded for the audit columns
     * @return the demo Staff identifiers, in seeded order
     */
    private List<UUID> insertStaff(JdbcTemplate jdbcTemplate, UUID auditUserId, LocalDate today, Timestamp now) {
        List<UUID> staffIds = new java.util.ArrayList<>();
        for (int index = 1; index <= STAFF_COUNT; index++) {
            staffIds.add(deterministicId(PREFIX + "STAFF-" + index));
        }
        Integer existingCount = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM staff WHERE id = ?", Integer.class, staffIds.get(0));
        if (existingCount != null && existingCount > 0) {
            return List.copyOf(staffIds);
        }

        for (int index = 1; index <= STAFF_COUNT; index++) {
            String firstName = FIRST_NAMES.get((index - 1) % FIRST_NAMES.size());
            String lastName = LAST_NAMES.get((index - 1) % LAST_NAMES.size());
            String position = STAFF_POSITIONS.get((index - 1) % STAFF_POSITIONS.size());
            boolean active = index > INACTIVE_STAFF_COUNT;
            jdbcTemplate.update(
                    "INSERT INTO staff (id, staff_code, first_name, last_name, phone, email, position, "
                            + "start_date, active, notes, created_at, created_by, updated_at, updated_by) "
                            + "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                    staffIds.get(index - 1), PREFIX + "STF-" + String.format("%03d", index), firstName, lastName,
                    "+8409" + String.format("%07d", index), "demo.staff" + index + "@example.test", position,
                    today.minusYears(1).minusMonths(index), active, null, now, auditUserId, now, auditUserId);
        }
        return List.copyOf(staffIds);
    }

    /**
     * Inserts a small, deterministic set of recent Daily Work Record rows for the demo Staff
     * members: several recent workdays for active Staff, and one older record for each inactive
     * Staff member to demonstrate that their history remains preserved after deactivation.
     *
     * @param jdbcTemplate JDBC access used to insert demo rows
     * @param staffIds demo Staff identifiers, in the same order {@link #insertStaff} seeded them
     * @param auditUserId demo marker user recorded as the creator/updater
     * @param today current hotel business date
     */
    private void insertDailyWorkRecords(
            JdbcTemplate jdbcTemplate, List<UUID> staffIds, UUID auditUserId, LocalDate today) {
        UUID firstRecordId = deterministicId(PREFIX + "WORK-1-0");
        Integer existingCount = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM daily_work_record WHERE id = ?", Integer.class, firstRecordId);
        if (existingCount != null && existingCount > 0) {
            return;
        }

        LocalTime startTime = LocalTime.of(8, 0);
        LocalTime endTime = LocalTime.of(17, 0);
        for (int staffIndex = 0; staffIndex < staffIds.size(); staffIndex++) {
            boolean active = staffIndex >= INACTIVE_STAFF_COUNT;
            int recordCount = active ? ACTIVE_STAFF_WORK_RECORD_DAYS : 1;
            for (int recordIndex = 0; recordIndex < recordCount; recordIndex++) {
                LocalDate workDate = active
                        ? today.minusDays(recordIndex + 1)
                        : today.minusDays(30 + recordIndex);
                Instant recordedAt = workDate.atTime(18, 0).atZone(BUSINESS_ZONE).toInstant();
                Timestamp recordedAtTimestamp = timestamp(recordedAt);
                jdbcTemplate.update(
                        "INSERT INTO daily_work_record (id, staff_id, work_date, start_time, end_time, notes, "
                                + "created_at, created_by, updated_at, updated_by) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                        deterministicId(PREFIX + "WORK-" + (staffIndex + 1) + "-" + recordIndex),
                        staffIds.get(staffIndex), workDate, startTime, endTime, null,
                        recordedAtTimestamp, auditUserId, recordedAtTimestamp, auditUserId);
            }
        }
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
            JdbcTemplate jdbcTemplate, Map<String, UUID> roomTypes, Map<UUID, String> roomNumbers,
            UUID auditUserId, Timestamp now) {
        List<UUID> roomIds = new java.util.ArrayList<>();
        insertRooms(jdbcTemplate, roomIds, roomNumbers, roomTypes, auditUserId, now, "SINGLE", 5, 101);
        insertRooms(jdbcTemplate, roomIds, roomNumbers, roomTypes, auditUserId, now, "DOUBLE", 6, 201);
        insertRooms(jdbcTemplate, roomIds, roomNumbers, roomTypes, auditUserId, now, "TWIN", 4, 301);
        insertRooms(jdbcTemplate, roomIds, roomNumbers, roomTypes, auditUserId, now, "TRIPLE", 4, 401);
        insertRooms(jdbcTemplate, roomIds, roomNumbers, roomTypes, auditUserId, now, "FAMILY", 3, 501);
        return List.copyOf(roomIds);
    }

    private void insertRooms(
            JdbcTemplate jdbcTemplate, List<UUID> roomIds, Map<UUID, String> roomNumbers,
            Map<String, UUID> roomTypes, UUID auditUserId,
            Timestamp now, String roomType, int count, int firstNumber) {
        UUID roomTypeId = roomTypes.get(roomType);
        if (roomTypeId == null) {
            throw new IllegalStateException("Approved RoomType is missing: " + roomType);
        }
        for (int index = 0; index < count; index++) {
            UUID roomId = deterministicId(PREFIX + "ROOM-" + (firstNumber + index));
            String roomNumber = PREFIX + (firstNumber + index);
            jdbcTemplate.update(
                    "INSERT INTO room (id, room_number, room_type_id, floor, status, active, created_at, "
                            + "created_by, updated_at, updated_by) VALUES (?, ?, ?, ?, 'AVAILABLE', TRUE, ?, ?, ?, ?)",
                    roomId, roomNumber, roomTypeId, String.valueOf(firstNumber / 100),
                    now, auditUserId, now, auditUserId);
            roomIds.add(roomId);
            roomNumbers.put(roomId, roomNumber);
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
            JdbcTemplate jdbcTemplate, List<UUID> guestIds, List<UUID> roomIds, Map<UUID, String> roomNumbers,
            UUID auditUserId, LocalDate today, Timestamp now) {
        Random random = new Random(RANDOM_SEED);
        Random sourceRandom = new Random(RANDOM_SEED);
        int reservationIndex = 0;
        for (int month = 1; month <= 12; month++) {
            for (int monthIndex = 0; monthIndex < MONTHLY_RESERVATION_COUNTS[month - 1]; monthIndex++) {
                LocalDate checkInDate = plannedCheckInDate(today, month, monthIndex);
                int nights = nightsFor(today, checkInDate, monthIndex, random);
                String status = reservationStatus(today, checkInDate, monthIndex);
                UUID reservationId = deterministicId(PREFIX + "RESERVATION-" + reservationIndex);
                String reservationNumber = String.format(
                        "R%s-%06d",
                        checkInDate.minusDays(21).format(java.time.format.DateTimeFormatter.BASIC_ISO_DATE),
                        reservationIndex + 1);
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
                UUID reservationRoomId = insertReservationRoom(
                        jdbcTemplate, reservationId, roomId, auditUserId, checkInDate, nights, reservedAtTimestamp,
                        updatedAt);
                if ("CHECKED_IN".equals(status) || "CHECKED_OUT".equals(status)) {
                    UUID stayId = insertStay(
                            jdbcTemplate, reservationId, auditUserId, checkInDate, nights, status,
                            reservedAtTimestamp, updatedAt);
                    insertRoomCharge(
                            jdbcTemplate, stayId, reservationRoomId, roomNumbers.get(roomId), auditUserId,
                            checkInDate, nights, reservedAtTimestamp, updatedAt);
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

    private UUID insertReservationRoom(
            JdbcTemplate jdbcTemplate, UUID reservationId, UUID roomId, UUID auditUserId,
            LocalDate checkInDate, int nights, Timestamp createdAt, Timestamp updatedAt) {
        BigDecimal rate = BigDecimal.valueOf(1_100_000L);
        UUID reservationRoomId = deterministicId(PREFIX + "RESERVATION-ROOM-" + reservationId);
        jdbcTemplate.update(
                "INSERT INTO reservation_room (id, reservation_id, room_id, check_in_date, check_out_date, "
                        + "nightly_rate, total_amount, created_at, created_by, updated_at, updated_by) "
                        + "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                reservationRoomId, reservationId, roomId,
                checkInDate, checkInDate.plusDays(nights), rate,
                rate.multiply(BigDecimal.valueOf(nights)), createdAt, auditUserId, updatedAt, auditUserId);
        return reservationRoomId;
    }

    private UUID insertStay(
            JdbcTemplate jdbcTemplate, UUID reservationId, UUID auditUserId, LocalDate checkInDate,
            int nights, String reservationStatus, Timestamp createdAt, Timestamp updatedAt) {
        Instant checkedInAt = checkInDate.atTime(14, 0).atZone(BUSINESS_ZONE).toInstant();
        Instant checkedOutAt = "CHECKED_OUT".equals(reservationStatus)
                ? checkInDate.plusDays(nights).atTime(11, 0).atZone(BUSINESS_ZONE).toInstant() : null;
        UUID stayId = deterministicId(PREFIX + "STAY-" + reservationId);
        jdbcTemplate.update(
                "INSERT INTO stay (id, reservation_id, status, actual_check_in_at, actual_check_out_at, "
                        + "created_at, created_by, updated_at, updated_by) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)",
                stayId, reservationId, reservationStatus,
                timestamp(checkedInAt), timestamp(checkedOutAt),
                createdAt, auditUserId, updatedAt, auditUserId);
        return stayId;
    }

    /**
     * Seeds the automatic ROOM Charge that {@code ReservationService.checkIn()} would have created for
     * this ReservationRoom, since seeded CHECKED_IN/CHECKED_OUT Stays are inserted directly and bypass
     * that production check-in flow. The amount is copied from the same nightly-rate/nights snapshot
     * used to seed the ReservationRoom, not independently recalculated.
     */
    private void insertRoomCharge(
            JdbcTemplate jdbcTemplate, UUID stayId, UUID reservationRoomId, String roomNumber,
            UUID auditUserId, LocalDate checkInDate, int nights, Timestamp createdAt, Timestamp updatedAt) {
        BigDecimal rate = BigDecimal.valueOf(1_100_000L);
        BigDecimal totalAmount = rate.multiply(BigDecimal.valueOf(nights));
        Instant chargedAt = checkInDate.atTime(14, 0).atZone(BUSINESS_ZONE).toInstant();
        jdbcTemplate.update(
                "INSERT INTO charge (id, stay_id, type, description, quantity, unit_price, amount, "
                        + "charged_at, created_at, created_by, updated_at, updated_by) "
                        + "VALUES (?, ?, 'ROOM', ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                deterministicId(PREFIX + "CHARGE-ROOM-" + reservationRoomId), stayId, "Room " + roomNumber,
                BigDecimal.valueOf(nights), rate, totalAmount, timestamp(chargedAt),
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

    /**
     * Seeds deterministic Expense demo data spanning all 12 months of the current demo year: one
     * recurring SALARY/ELECTRICITY/WATER/INTERNET Expense per month, plus
     * {@value #OCCASIONAL_EXPENSES_PER_MONTH} occasional Expenses per month rotated across the
     * remaining approved categories. Every month other than the current demo month is treated as
     * settled historical accounting data and is predominantly POSTED; only the current demo month
     * favors DRAFT/SUBMITTED/APPROVED to leave useful in-progress lifecycle data for manual
     * testing, while still supporting a realistically POSTED-heavy full year for later Monthly
     * Financial Report work.
     *
     * @param jdbcTemplate JDBC access used to insert Expense rows
     * @param auditUserId demo audit user recorded as creator, updater, and approver
     * @param today demo "current" date, used to select the seeded year and the historical/recent split
     */
    private void insertExpenses(JdbcTemplate jdbcTemplate, UUID auditUserId, LocalDate today) {
        Map<String, UUID> categoryIds = expenseCategoryIds(jdbcTemplate);
        Random amountRandom = new Random(RANDOM_SEED);
        int year = today.getYear();
        int currentMonth = today.getMonthValue();
        int sequenceIndex = 0;

        for (int month = 1; month <= 12; month++) {
            boolean historical = month != currentMonth;
            String monthName = Month.of(month).getDisplayName(TextStyle.FULL, Locale.ENGLISH);

            sequenceIndex = insertExpense(
                    jdbcTemplate, categoryIds.get("SALARY"), auditUserId,
                    roundedAmount(amountRandom, 15_000_000L, 30_000_000L, 500_000L),
                    LocalDate.of(year, month, 28), "BANK_TRANSFER",
                    "Staff salary - " + monthName, historical, sequenceIndex);
            sequenceIndex = insertExpense(
                    jdbcTemplate, categoryIds.get("ELECTRICITY"), auditUserId,
                    roundedAmount(amountRandom, 2_000_000L, 6_000_000L, 50_000L),
                    LocalDate.of(year, month, 10), "BANK_TRANSFER",
                    monthName + " electricity bill", historical, sequenceIndex);
            sequenceIndex = insertExpense(
                    jdbcTemplate, categoryIds.get("WATER"), auditUserId,
                    roundedAmount(amountRandom, 500_000L, 1_500_000L, 10_000L),
                    LocalDate.of(year, month, 12), "BANK_TRANSFER",
                    monthName + " water bill", historical, sequenceIndex);
            sequenceIndex = insertExpense(
                    jdbcTemplate, categoryIds.get("INTERNET"), auditUserId,
                    roundedAmount(amountRandom, 300_000L, 800_000L, 10_000L),
                    LocalDate.of(year, month, 15), "BANK_TRANSFER",
                    monthName + " internet bill", historical, sequenceIndex);

            for (int occasionalIndex = 0; occasionalIndex < OCCASIONAL_EXPENSES_PER_MONTH; occasionalIndex++) {
                String category = OCCASIONAL_EXPENSE_CATEGORIES.get(
                        (month - 1 + occasionalIndex) % OCCASIONAL_EXPENSE_CATEGORIES.size());
                List<String> descriptions = OCCASIONAL_EXPENSE_DESCRIPTIONS.get(category);
                String description = descriptions.get((month - 1 + occasionalIndex) % descriptions.size());
                if ("OTA_COMMISSION".equals(category)) {
                    description = description + " - " + monthName;
                }
                BigDecimal amount = occasionalAmount(amountRandom, category);
                LocalDate expenseDate = LocalDate.of(year, month, Math.min(28, 3 + occasionalIndex * 6));
                String paymentMethod = occasionalPaymentMethod(category, amount, sequenceIndex);
                sequenceIndex = insertExpense(
                        jdbcTemplate, categoryIds.get(category), auditUserId, amount, expenseDate,
                        paymentMethod, description, historical, sequenceIndex);
            }
        }
    }

    /** Resolves every approved Expense category code to its technical identifier. */
    private Map<String, UUID> expenseCategoryIds(JdbcTemplate jdbcTemplate) {
        Map<String, UUID> categoryIds = jdbcTemplate.query(
                "SELECT id, code FROM expense_category WHERE code IN ("
                        + String.join(",", java.util.Collections.nCopies(EXPENSE_CATEGORY_CODES.size(), "?"))
                        + ")",
                resultSet -> {
                    Map<String, UUID> values = new java.util.HashMap<>();
                    while (resultSet.next()) {
                        values.put(resultSet.getString("code"), resultSet.getObject("id", UUID.class));
                    }
                    return values;
                },
                EXPENSE_CATEGORY_CODES.toArray());
        for (String code : EXPENSE_CATEGORY_CODES) {
            if (categoryIds.get(code) == null) {
                throw new IllegalStateException("Approved Expense category is missing: " + code);
            }
        }
        return categoryIds;
    }

    /**
     * Inserts one deterministic Expense row with a lifecycle-consistent status and approver.
     *
     * @param sequenceIndex running Expense sequence number, used for the deterministic id and status cycle
     * @return the next sequence index
     */
    private int insertExpense(
            JdbcTemplate jdbcTemplate, UUID categoryId, UUID auditUserId, BigDecimal amount,
            LocalDate expenseDate, String paymentMethod, String description, boolean historical,
            int sequenceIndex) {
        String status = statusFor(historical, sequenceIndex);
        boolean approved = "APPROVED".equals(status) || "POSTED".equals(status);
        UUID approvedBy = approved ? auditUserId : null;
        Instant createdAt = expenseDate.atTime(9, 0).atZone(BUSINESS_ZONE).toInstant();
        Instant updatedAt = approved ? expenseDate.plusDays(1).atTime(9, 0).atZone(BUSINESS_ZONE).toInstant() : createdAt;
        UUID expenseId = deterministicId(PREFIX + "EXPENSE-" + sequenceIndex);
        jdbcTemplate.update(
                "INSERT INTO expense (id, category_id, amount, currency, expense_date, payment_method, "
                        + "description, status, approved_by, created_at, created_by, updated_at, updated_by) "
                        + "VALUES (?, ?, ?, 'VND', ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                expenseId, categoryId, amount, expenseDate, paymentMethod, description, status, approvedBy,
                timestamp(createdAt), auditUserId, timestamp(updatedAt), auditUserId);
        return sequenceIndex + 1;
    }

    /** Selects a deterministic lifecycle status for one Expense sequence position. */
    private String statusFor(boolean historical, int sequenceIndex) {
        if (!historical) {
            return RECENT_STATUS_CYCLE.get(sequenceIndex % RECENT_STATUS_CYCLE.size());
        }
        if (sequenceIndex % REJECTED_EVERY_NTH_HISTORICAL_EXPENSE == 0) {
            return "REJECTED";
        }
        return HISTORICAL_STATUS_CYCLE.get(sequenceIndex % HISTORICAL_STATUS_CYCLE.size());
    }

    /** Generates a deterministic, human-friendly whole-VND amount rounded to the given step. */
    private BigDecimal roundedAmount(Random random, long minInclusive, long maxInclusive, long step) {
        long steps = (maxInclusive - minInclusive) / step;
        long value = minInclusive + random.nextInt((int) steps + 1) * step;
        return BigDecimal.valueOf(value);
    }

    /** Generates a deterministic occasional-category amount using a category-appropriate demo range. */
    private BigDecimal occasionalAmount(Random random, String category) {
        return switch (category) {
            case "MAINTENANCE" -> roundedAmount(random, 500_000L, 3_000_000L, 50_000L);
            case "REPAIR" -> roundedAmount(random, 300_000L, 2_500_000L, 50_000L);
            case "CONSTRUCTION" -> roundedAmount(random, 3_000_000L, 15_000_000L, 100_000L);
            case "SUPPLIES" -> roundedAmount(random, 200_000L, 2_000_000L, 10_000L);
            case "LAUNDRY" -> roundedAmount(random, 300_000L, 1_500_000L, 10_000L);
            case "CLEANING" -> roundedAmount(random, 200_000L, 1_200_000L, 10_000L);
            case "OTA_COMMISSION" -> roundedAmount(random, 1_000_000L, 8_000_000L, 50_000L);
            default -> roundedAmount(random, 200_000L, 2_000_000L, 10_000L);
        };
    }

    /** Selects a realistic demo payment method for one occasional Expense category. */
    private String occasionalPaymentMethod(String category, BigDecimal amount, int sequenceIndex) {
        if ("CONSTRUCTION".equals(category) || "OTA_COMMISSION".equals(category)) {
            return "BANK_TRANSFER";
        }
        if ("MAINTENANCE".equals(category)) {
            return amount.compareTo(BigDecimal.valueOf(2_000_000L)) >= 0 ? "BANK_TRANSFER" : "CASH";
        }
        if ("SUPPLIES".equals(category) || "CLEANING".equals(category)) {
            return "CASH";
        }
        if ("REPAIR".equals(category) || "LAUNDRY".equals(category)) {
            return sequenceIndex % 5 == 0 ? "CREDIT_CARD" : "CASH";
        }
        return sequenceIndex % 4 == 0 ? "OTHER" : sequenceIndex % 4 == 1 ? "CREDIT_CARD" : "CASH";
    }

    /**
     * Seeds 36 deterministic Additional Revenue records — exactly three in every current business
     * year month — without changing any existing demo rows. The records retain stable UUIDs. A
     * previously marked dataset is backfilled only when this deterministic slice is absent.
     */
    private void insertAdditionalRevenues(JdbcTemplate jdbcTemplate, UUID auditUserId, LocalDate today) {
        UUID firstRevenueId = deterministicId(PREFIX + "ADDITIONAL-REVENUE-0");
        Integer existingCount = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM additional_revenue WHERE id = ?", Integer.class, firstRevenueId);
        if (existingCount != null && existingCount > 0) {
            return;
        }

        Map<String, UUID> categoryIds = additionalRevenueCategoryIds(jdbcTemplate);
        int sequenceIndex = 0;
        for (int month = 1; month <= 12; month++) {
            for (int recordInMonth = 0; recordInMonth < 3; recordInMonth++) {
                boolean otherCategory = OTHER_ADDITIONAL_REVENUE_INDICES.contains(sequenceIndex);
                String categoryCode = otherCategory ? "OTHER" : "ELECTRIC_CART_RENTAL";
                LocalDate revenueDate = LocalDate.of(today.getYear(), month, List.of(5, 14, 23).get(recordInMonth));
                boolean voided = VOIDED_ADDITIONAL_REVENUE_INDICES.contains(sequenceIndex);
                Instant createdAt = revenueDate.atTime(9, 0).atZone(BUSINESS_ZONE).toInstant();
                Instant voidedAt = voided
                        ? revenueDate.plusDays(1).atTime(10, 0).atZone(BUSINESS_ZONE).toInstant()
                        : null;
                String status = voided ? "VOIDED" : "RECORDED";
                String voidReason = voided
                        ? ADDITIONAL_REVENUE_VOID_REASONS.get(VOIDED_ADDITIONAL_REVENUE_INDICES.indexOf(sequenceIndex))
                        : null;
                jdbcTemplate.update(
                        "INSERT INTO additional_revenue (id, category_id, amount, currency, revenue_date, "
                                + "payment_method, description, status, void_reason, voided_at, voided_by, "
                                + "created_at, created_by, updated_at, updated_by) "
                                + "VALUES (?, ?, ?, 'VND', ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                        deterministicId(PREFIX + "ADDITIONAL-REVENUE-" + sequenceIndex),
                        categoryIds.get(categoryCode),
                        additionalRevenueAmount(otherCategory, sequenceIndex),
                        revenueDate,
                        additionalRevenuePaymentMethod(sequenceIndex),
                        additionalRevenueDescription(otherCategory, month),
                        status,
                        voidReason,
                        timestamp(voidedAt),
                        voided ? auditUserId : null,
                        timestamp(createdAt),
                        auditUserId,
                        timestamp(voided ? voidedAt : createdAt),
                        auditUserId);
                sequenceIndex++;
            }
        }
    }

    /** Resolves the Task 1 reference categories rather than embedding their database UUIDs. */
    private Map<String, UUID> additionalRevenueCategoryIds(JdbcTemplate jdbcTemplate) {
        Map<String, UUID> categoryIds = jdbcTemplate.query(
                "SELECT id, code FROM additional_revenue_category WHERE code IN (?, ?)",
                resultSet -> {
                    Map<String, UUID> values = new java.util.HashMap<>();
                    while (resultSet.next()) {
                        values.put(resultSet.getString("code"), resultSet.getObject("id", UUID.class));
                    }
                    return values;
                },
                ADDITIONAL_REVENUE_CATEGORY_CODES.toArray());
        for (String code : ADDITIONAL_REVENUE_CATEGORY_CODES) {
            if (categoryIds.get(code) == null) {
                throw new IllegalStateException("Approved Additional Revenue category is missing: " + code);
            }
        }
        return categoryIds;
    }

    private BigDecimal additionalRevenueAmount(boolean otherCategory, int sequenceIndex) {
        if (otherCategory) {
            return BigDecimal.valueOf(50_000L + (sequenceIndex % 20) * 50_000L);
        }
        return BigDecimal.valueOf(100_000L + (sequenceIndex % 41) * 10_000L);
    }

    private String additionalRevenuePaymentMethod(int sequenceIndex) {
        if (BANK_TRANSFER_ADDITIONAL_REVENUE_INDICES.contains(sequenceIndex)) {
            return "BANK_TRANSFER";
        }
        if (CREDIT_CARD_ADDITIONAL_REVENUE_INDICES.contains(sequenceIndex)) {
            return "CREDIT_CARD";
        }
        if (OTHER_PAYMENT_ADDITIONAL_REVENUE_INDICES.contains(sequenceIndex)) {
            return "OTHER";
        }
        return "CASH";
    }

    private String additionalRevenueDescription(boolean otherCategory, int month) {
        if (otherCategory) {
            return List.of("Guest transport service", "Miscellaneous hotel revenue")
                    .get(month % 2);
        }
        return "Electric cart rental";
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
