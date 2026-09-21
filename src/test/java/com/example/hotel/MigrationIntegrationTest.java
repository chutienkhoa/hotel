package com.example.hotel;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.example.hotel.entity.common.Role;
import com.example.hotel.entity.customer.Guest;
import com.example.hotel.repository.booking.ReservationRepository;
import com.example.hotel.repository.common.AppUserRepository;
import com.example.hotel.repository.common.RoleRepository;
import com.example.hotel.repository.customer.GuestRepository;
import java.util.Set;
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

/** Verifies that Flyway applies the complete migration set to PostgreSQL. */
@SpringBootTest
@Testcontainers(disabledWithoutDocker = true)
class MigrationIntegrationTest {
    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");

    /**
     * Cung cấp thông tin kết nối của PostgreSQL Testcontainers cho Spring.
     *
     * @param registry registry chứa các property động
     */
    @DynamicPropertySource
    static void database(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", postgres::getJdbcUrl);
        registry.add("spring.datasource.username", postgres::getUsername);
        registry.add("spring.datasource.password", postgres::getPassword);
    }

    @Autowired
    org.flywaydb.core.Flyway flyway;

    @Autowired
    RoleRepository roleRepository;

    @Autowired
    AppUserRepository appUserRepository;

    @Autowired
    ReservationRepository reservationRepository;

    @Autowired
    GuestRepository guestRepository;

    @Autowired
    JdbcTemplate jdbcTemplate;

    /** Xác nhận không còn migration chờ và các migration permission đã được áp dụng. */
    @Test
    void migrationIsCurrent() {
        assertEquals(0, flyway.info().pending().length);
        assertEquals(27, flyway.info().applied().length);
    }

    /** Verifies the exact role-permission mappings required by the approved operational flow. */
    @Test
    void permissionMappingMatchesSpecification() {
        assertPermissionCodes(
                "ADMIN",
                Set.of(
                        "MANAGE_USER",
                        "MANAGE_ROOM",
                        "MANAGE_BOOKING",
                        "MANAGE_PAYMENT",
                        "MANAGE_EXPENSE",
                        "MANAGE_ADDITIONAL_REVENUE",
                        "MANAGE_GUEST",
                        "VIEW_REPORT",
                        "VIEW_BOOKING",
                        "CHECK_OUT",
                        "CHANGE_ROOM",
                        "MANAGE_STAFF",
                        "MANAGE_ATTENDANCE"));
        assertPermissionCodes(
                "MANAGER",
                Set.of(
                        "MANAGE_ROOM",
                        "MANAGE_BOOKING",
                        "MANAGE_PAYMENT",
                        "MANAGE_EXPENSE",
                        "MANAGE_ADDITIONAL_REVENUE",
                        "VIEW_REPORT",
                        "MANAGE_GUEST",
                        "VIEW_BOOKING",
                        "CHECK_IN",
                        "CHECK_OUT",
                        "CHANGE_ROOM",
                        "MANAGE_STAFF",
                        "MANAGE_ATTENDANCE"));
        assertPermissionCodes(
                "STAFF",
                Set.of("VIEW_BOOKING", "CHECK_IN", "CHECK_OUT", "MANAGE_PAYMENT", "CHANGE_ROOM"));
        assertRolePermissionRelationshipCount("ADMIN", "VIEW_REPORT", 1);
        assertRolePermissionRelationshipCount("MANAGER", "VIEW_REPORT", 1);
        assertRolePermissionRelationshipCount("STAFF", "VIEW_REPORT", 0);
        assertRolePermissionRelationshipCount("ADMIN", "CHECK_OUT", 1);
        assertRolePermissionRelationshipCount("MANAGER", "CHECK_OUT", 1);
        assertRolePermissionRelationshipCount("STAFF", "CHECK_OUT", 1);
        assertRolePermissionRelationshipCount("ADMIN", "MANAGE_EXPENSE", 1);
        assertRolePermissionRelationshipCount("MANAGER", "MANAGE_EXPENSE", 1);
        assertRolePermissionRelationshipCount("STAFF", "MANAGE_EXPENSE", 0);
        assertRolePermissionRelationshipCount("ADMIN", "MANAGE_ADDITIONAL_REVENUE", 1);
        assertRolePermissionRelationshipCount("MANAGER", "MANAGE_ADDITIONAL_REVENUE", 1);
        assertRolePermissionRelationshipCount("STAFF", "MANAGE_ADDITIONAL_REVENUE", 0);
        assertRolePermissionRelationshipCount("ADMIN", "MANAGE_PAYMENT", 1);
        assertRolePermissionRelationshipCount("MANAGER", "MANAGE_PAYMENT", 1);
        assertRolePermissionRelationshipCount("STAFF", "MANAGE_PAYMENT", 1);
        assertRolePermissionRelationshipCount("MANAGER", "CHECK_IN", 1);
        assertRolePermissionRelationshipCount("STAFF", "CHECK_IN", 1);
        assertRolePermissionRelationshipCount("ADMIN", "CHANGE_ROOM", 1);
        assertRolePermissionRelationshipCount("MANAGER", "CHANGE_ROOM", 1);
        assertRolePermissionRelationshipCount("STAFF", "CHANGE_ROOM", 1);
        assertRolePermissionRelationshipCount("STAFF", "MANAGE_BOOKING", 0);
        assertRolePermissionRelationshipCount("ADMIN", "MANAGE_STAFF", 1);
        assertRolePermissionRelationshipCount("MANAGER", "MANAGE_STAFF", 1);
        assertRolePermissionRelationshipCount("STAFF", "MANAGE_STAFF", 0);
        assertRolePermissionRelationshipCount("ADMIN", "MANAGE_ATTENDANCE", 1);
        assertRolePermissionRelationshipCount("MANAGER", "MANAGE_ATTENDANCE", 1);
        assertRolePermissionRelationshipCount("STAFF", "MANAGE_ATTENDANCE", 0);
    }

    /** Verifies V16 preserves MANAGE_GUEST and adds a distinct Additional Revenue permission. */
    @Test
    void additionalRevenuePermissionUsesAUniqueDeterministicId() {
        String guestPermissionCode = jdbcTemplate.queryForObject(
                "SELECT code FROM permission WHERE id = '00000000-0000-0000-0000-000000000111'",
                String.class);
        UUID additionalRevenuePermissionId = jdbcTemplate.queryForObject(
                "SELECT id FROM permission WHERE code = 'MANAGE_ADDITIONAL_REVENUE'", UUID.class);
        Integer additionalRevenuePermissionCount = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM permission WHERE code = 'MANAGE_ADDITIONAL_REVENUE'", Integer.class);
        Integer uniquePermissionIdCount = jdbcTemplate.queryForObject(
                "SELECT COUNT(DISTINCT id) FROM permission", Integer.class);
        Integer permissionCount = jdbcTemplate.queryForObject("SELECT COUNT(*) FROM permission", Integer.class);
        Integer additionalRevenueCategoryCount = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM additional_revenue_category "
                        + "WHERE (id = '00000000-0000-0000-0000-000000000401' "
                        + "AND code = 'ELECTRIC_CART_RENTAL') "
                        + "OR (id = '00000000-0000-0000-0000-000000000402' AND code = 'OTHER')",
                Integer.class);

        assertEquals("MANAGE_GUEST", guestPermissionCode);
        assertEquals(UUID.fromString("00000000-0000-0000-0000-000000000112"), additionalRevenuePermissionId);
        assertEquals(1, additionalRevenuePermissionCount);
        assertEquals(permissionCount, uniquePermissionIdCount);
        assertEquals(2, additionalRevenueCategoryCount);
    }

    /**
     * Verifies V26 creates the Staff and Daily Work Record tables with the approved constraints:
     * a unique Staff Code, a unique (staff_id, work_date) pair, a start-before-end CHECK, and
     * Staff/app_user foreign keys.
     */
    @Test
    void staffAndDailyWorkRecordSchemaMatchesV1Rules() {
        Boolean staffCodeIsUnique = jdbcTemplate.queryForObject(
                "SELECT EXISTS (SELECT 1 FROM pg_constraint c "
                        + "JOIN pg_class t ON t.oid = c.conrelid "
                        + "WHERE t.relname = 'staff' AND c.contype = 'u')",
                Boolean.class);
        Boolean staffDateUniqueIndexExists = jdbcTemplate.queryForObject(
                "SELECT EXISTS (SELECT 1 FROM pg_indexes "
                        + "WHERE tablename = 'daily_work_record' AND indexname = 'ux_daily_work_record_staff_date')",
                Boolean.class);
        Boolean startBeforeEndCheckExists = jdbcTemplate.queryForObject(
                "SELECT EXISTS (SELECT 1 FROM pg_constraint "
                        + "WHERE conname = 'daily_work_record_start_before_end')",
                Boolean.class);
        String startBeforeEndCheckDefinition = jdbcTemplate.queryForObject(
                "SELECT pg_get_constraintdef(oid) FROM pg_constraint "
                        + "WHERE conname = 'daily_work_record_start_before_end'",
                String.class);
        Boolean staffIdForeignKeyExists = jdbcTemplate.queryForObject(
                "SELECT EXISTS (SELECT 1 FROM information_schema.table_constraints tc "
                        + "JOIN information_schema.constraint_column_usage ccu "
                        + "ON tc.constraint_name = ccu.constraint_name "
                        + "WHERE tc.table_name = 'daily_work_record' AND tc.constraint_type = 'FOREIGN KEY' "
                        + "AND ccu.table_name = 'staff')",
                Boolean.class);
        Boolean staffIdIsRequired = jdbcTemplate.queryForObject(
                "SELECT is_nullable = 'NO' FROM information_schema.columns "
                        + "WHERE table_name = 'daily_work_record' AND column_name = 'staff_id'",
                Boolean.class);
        Boolean activeIsRequired = jdbcTemplate.queryForObject(
                "SELECT is_nullable = 'NO' FROM information_schema.columns "
                        + "WHERE table_name = 'staff' AND column_name = 'active'",
                Boolean.class);

        assertTrue(Boolean.TRUE.equals(staffCodeIsUnique));
        assertTrue(Boolean.TRUE.equals(staffDateUniqueIndexExists));
        assertTrue(Boolean.TRUE.equals(startBeforeEndCheckExists));
        assertTrue(startBeforeEndCheckDefinition.contains("start_time") && startBeforeEndCheckDefinition.contains("end_time"));
        assertTrue(Boolean.TRUE.equals(staffIdForeignKeyExists));
        assertTrue(Boolean.TRUE.equals(staffIdIsRequired));
        assertTrue(Boolean.TRUE.equals(activeIsRequired));
    }

    /** Verifies the V27 user-management schema: nullable unique Staff link and case-insensitive usernames. */
    @Test
    void userManagementSchemaMatchesV1Rules() {
        Boolean appUserIdIsNullable = jdbcTemplate.queryForObject(
                "SELECT is_nullable = 'YES' FROM information_schema.columns "
                        + "WHERE table_name = 'staff' AND column_name = 'app_user_id'",
                Boolean.class);
        Boolean appUserIdIsUnique = jdbcTemplate.queryForObject(
                "SELECT EXISTS (SELECT 1 FROM pg_constraint WHERE conname = 'uq_staff_app_user' AND contype = 'u')",
                Boolean.class);
        Boolean appUserForeignKeyExists = jdbcTemplate.queryForObject(
                "SELECT EXISTS (SELECT 1 FROM pg_constraint WHERE conname = 'fk_staff_app_user' AND contype = 'f')",
                Boolean.class);
        Boolean lowerUsernameIndexExists = jdbcTemplate.queryForObject(
                "SELECT EXISTS (SELECT 1 FROM pg_indexes "
                        + "WHERE tablename = 'app_user' AND indexname = 'ux_app_user_username_lower')",
                Boolean.class);

        assertTrue(Boolean.TRUE.equals(appUserIdIsNullable));
        assertTrue(Boolean.TRUE.equals(appUserIdIsUnique));
        assertTrue(Boolean.TRUE.equals(appUserForeignKeyExists));
        assertTrue(Boolean.TRUE.equals(lowerUsernameIndexExists));
    }

    /** Verifies usernames differing only by letter case cannot both be stored. */
    @Test
    @Transactional
    void appUserUsernameUniquenessIsCaseInsensitive() {
        insertUser(UUID.randomUUID(), "CaseUser");

        assertThrows(org.springframework.dao.DataIntegrityViolationException.class,
                () -> insertUser(UUID.randomUUID(), "caseuser"));
    }

    /** Verifies one account can be linked to at most one Staff member. */
    @Test
    @Transactional
    void staffAppUserLinkIsUnique() {
        UUID userId = UUID.randomUUID();
        insertUser(userId, "linked.user");
        insertStaff("MIG-STF-1", userId, userId);

        assertThrows(org.springframework.dao.DataIntegrityViolationException.class,
                () -> insertStaff("MIG-STF-2", userId, userId));
    }

    /** Verifies the last-active-ADMIN row-lock query is valid PostgreSQL and finds active ADMIN accounts. */
    @Test
    @Transactional
    void lockActiveAdminIdsFindsActiveAdmins() {
        UUID adminId = UUID.randomUUID();
        insertUser(adminId, "lock.admin");
        jdbcTemplate.update(
                "INSERT INTO user_role (user_id, role_id) SELECT ?, id FROM role WHERE code = 'ADMIN'", adminId);

        assertTrue(appUserRepository.lockActiveAdminIds().contains(adminId));
    }

    private void insertUser(UUID id, String username) {
        jdbcTemplate.update(
                "INSERT INTO app_user (id, username, password_hash, active, created_at, updated_at) "
                        + "VALUES (?, ?, 'hash', TRUE, now(), now())",
                id, username);
    }

    private void insertStaff(String staffCode, UUID appUserId, UUID auditUserId) {
        jdbcTemplate.update(
                "INSERT INTO staff (id, staff_code, first_name, last_name, start_date, active, app_user_id, "
                        + "created_at, created_by, updated_at, updated_by) "
                        + "VALUES (?, ?, 'A', 'B', CURRENT_DATE, TRUE, ?, now(), ?, now(), ?)",
                UUID.randomUUID(), staffCode, appUserId, auditUserId, auditUserId);
    }

    /** Verifies inserting two Daily Work Record rows for the same Staff/date is rejected by the unique index. */
    @Test
    @Transactional
    void dailyWorkRecordRejectsDuplicateStaffDatePair() {
        UUID userId = UUID.randomUUID();
        jdbcTemplate.update(
                "INSERT INTO app_user (id, username, password_hash, active, created_at, created_by, "
                        + "updated_at, updated_by) VALUES (?, ?, 'x', TRUE, NOW(), ?, NOW(), ?)",
                userId, "staff-migration-" + userId, userId, userId);
        UUID staffId = UUID.randomUUID();
        jdbcTemplate.update(
                "INSERT INTO staff (id, staff_code, first_name, last_name, start_date, active, created_at, "
                        + "created_by, updated_at, updated_by) "
                        + "VALUES (?, ?, 'First', 'Last', CURRENT_DATE, TRUE, NOW(), ?, NOW(), ?)",
                staffId, "STF-TEST-" + staffId.toString().substring(0, 8), userId, userId);
        jdbcTemplate.update(
                "INSERT INTO daily_work_record (id, staff_id, work_date, start_time, end_time, created_at, "
                        + "created_by, updated_at, updated_by) "
                        + "VALUES (?, ?, CURRENT_DATE, '08:00', '17:00', NOW(), ?, NOW(), ?)",
                UUID.randomUUID(), staffId, userId, userId);

        org.springframework.dao.DataIntegrityViolationException exception = org.junit.jupiter.api.Assertions.assertThrows(
                org.springframework.dao.DataIntegrityViolationException.class,
                () -> jdbcTemplate.update(
                        "INSERT INTO daily_work_record (id, staff_id, work_date, start_time, end_time, "
                                + "created_at, created_by, updated_at, updated_by) "
                                + "VALUES (?, ?, CURRENT_DATE, '09:00', '18:00', NOW(), ?, NOW(), ?)",
                        UUID.randomUUID(), staffId, userId, userId));

        assertTrue(exception.getMessage().toLowerCase(java.util.Locale.ROOT).contains("duplicate")
                || exception.getMessage().toLowerCase(java.util.Locale.ROOT).contains("unique"));
    }

    /** Verifies the database rejects a Daily Work Record row where Start is not strictly before End. */
    @Test
    @Transactional
    void dailyWorkRecordRejectsStartNotBeforeEnd() {
        UUID userId = UUID.randomUUID();
        jdbcTemplate.update(
                "INSERT INTO app_user (id, username, password_hash, active, created_at, created_by, "
                        + "updated_at, updated_by) VALUES (?, ?, 'x', TRUE, NOW(), ?, NOW(), ?)",
                userId, "staff-check-" + userId, userId, userId);
        UUID staffId = UUID.randomUUID();
        jdbcTemplate.update(
                "INSERT INTO staff (id, staff_code, first_name, last_name, start_date, active, created_at, "
                        + "created_by, updated_at, updated_by) "
                        + "VALUES (?, ?, 'First', 'Last', CURRENT_DATE, TRUE, NOW(), ?, NOW(), ?)",
                staffId, "STF-CHK-" + staffId.toString().substring(0, 8), userId, userId);

        assertThrows(
                org.springframework.dao.DataIntegrityViolationException.class,
                () -> jdbcTemplate.update(
                        "INSERT INTO daily_work_record (id, staff_id, work_date, start_time, end_time, "
                                + "created_at, created_by, updated_at, updated_by) "
                                + "VALUES (?, ?, CURRENT_DATE, '17:00', '08:00', NOW(), ?, NOW(), ?)",
                        UUID.randomUUID(), staffId, userId, userId));
    }

    /** Verifies V17 aligns Additional Revenue currency with the validated VARCHAR convention. */
    @Test
    void additionalRevenueCurrencyUsesVarcharAndRetainsTheVndConstraint() {
        String currencyType = jdbcTemplate.queryForObject(
                "SELECT data_type FROM information_schema.columns "
                        + "WHERE table_name = 'additional_revenue' AND column_name = 'currency'",
                String.class);
        Boolean vndConstraintExists = jdbcTemplate.queryForObject(
                "SELECT EXISTS (SELECT 1 FROM pg_constraint "
                        + "WHERE conname = 'additional_revenue_currency_vnd')",
                Boolean.class);
        String vndConstraintDefinition = jdbcTemplate.queryForObject(
                "SELECT pg_get_constraintdef(oid) FROM pg_constraint "
                        + "WHERE conname = 'additional_revenue_currency_vnd'",
                String.class);

        assertEquals("character varying", currencyType);
        assertTrue(Boolean.TRUE.equals(vndConstraintExists));
        assertTrue(vndConstraintDefinition.contains("'VND'"));
    }

    /** Verifies V18 creates the Guest document metadata table with a required Guest relationship. */
    @Test
    void guestDocumentSchemaRequiresGuestRelationship() {
        Boolean guestIdIsRequired = jdbcTemplate.queryForObject(
                "SELECT is_nullable = 'NO' FROM information_schema.columns "
                        + "WHERE table_name = 'guest_document' AND column_name = 'guest_id'",
                Boolean.class);

        assertTrue(Boolean.TRUE.equals(guestIdIsRequired));
    }

    /** Verifies V25 removes the one-passport-per-Guest constraint so a Guest may own many. */
    @Test
    void guestDocumentSchemaAllowsMultiplePassportImagesPerGuest() {
        Boolean uniquePassportConstraintExists = jdbcTemplate.queryForObject(
                "SELECT EXISTS (SELECT 1 FROM pg_constraint "
                        + "WHERE conname = 'guest_document_one_per_type')",
                Boolean.class);

        assertTrue(Boolean.FALSE.equals(uniquePassportConstraintExists));
    }

    /** Verifies V25 actually permits inserting two PASSPORT_IMAGE rows for the same Guest. */
    @Test
    @Transactional
    void guestDocumentTableAcceptsMultiplePassportImageRowsForOneGuest() {
        UUID guestId = UUID.randomUUID();
        UUID userId = UUID.randomUUID();
        jdbcTemplate.update(
                "INSERT INTO app_user (id, username, password_hash, active, created_at, created_by, "
                        + "updated_at, updated_by) VALUES (?, ?, 'x', TRUE, NOW(), ?, NOW(), ?)",
                userId, "seed-" + userId, userId, userId);
        jdbcTemplate.update(
                "INSERT INTO guest (id, guest_code, first_name, last_name, nationality, created_at, created_by, "
                        + "updated_at, updated_by) VALUES (?, ?, 'First', 'Last', 'Vietnam', NOW(), ?, NOW(), ?)",
                guestId, "G900001", userId, userId);

        jdbcTemplate.update(
                "INSERT INTO guest_document (id, guest_id, document_type, original_name, content_type, file_size, "
                        + "storage_key, created_at, created_by, updated_at, updated_by) "
                        + "VALUES (?, ?, 'PASSPORT_IMAGE', 'p1.jpg', 'image/jpeg', 10, ?, NOW(), ?, NOW(), ?)",
                UUID.randomUUID(), guestId, UUID.randomUUID() + ".jpg", userId, userId);
        jdbcTemplate.update(
                "INSERT INTO guest_document (id, guest_id, document_type, original_name, content_type, file_size, "
                        + "storage_key, created_at, created_by, updated_at, updated_by) "
                        + "VALUES (?, ?, 'PASSPORT_IMAGE', 'p2.jpg', 'image/jpeg', 10, ?, NOW(), ?, NOW(), ?)",
                UUID.randomUUID(), guestId, UUID.randomUUID() + ".jpg", userId, userId);

        Integer passportCount = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM guest_document WHERE guest_id = ? AND document_type = 'PASSPORT_IMAGE'",
                Integer.class,
                guestId);

        assertEquals(2, passportCount);
    }

    /** Verifies V19 adds a nullable, unconstrained OTA booking reference column to reservation. */
    @Test
    void reservationOtaBookingReferenceColumnIsNullableAndUnconstrained() {
        Boolean columnIsNullable = jdbcTemplate.queryForObject(
                "SELECT is_nullable = 'YES' FROM information_schema.columns "
                        + "WHERE table_name = 'reservation' AND column_name = 'ota_booking_reference'",
                Boolean.class);
        Integer uniqueConstraintCount = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM pg_constraint c "
                        + "JOIN pg_attribute a ON a.attrelid = c.conrelid AND a.attnum = ANY(c.conkey) "
                        + "WHERE c.conrelid = 'reservation'::regclass "
                        + "AND c.contype = 'u' AND a.attname = 'ota_booking_reference'",
                Integer.class);

        assertTrue(Boolean.TRUE.equals(columnIsNullable));
        assertEquals(0, uniqueConstraintCount);
    }

    /** Verifies that PostgreSQL allocates distinct, correctly formatted reservation numbers. */
    @Test
    @Transactional
    void reservationNumberSequenceGeneratesUniqueDailyNumbers() {
        String firstReservationNumber = reservationRepository.allocateReservationNumber().orElseThrow();
        String secondReservationNumber = reservationRepository.allocateReservationNumber().orElseThrow();

        assertTrue(firstReservationNumber.matches("R\\d{8}-000001"));
        assertTrue(secondReservationNumber.matches("R\\d{8}-000002"));
    }

    /** Verifies the Room table requires a RoomType after the approved Room Management migration. */
    @Test
    void roomTypeIsRequiredForRooms() {
        Boolean roomTypeIsRequired = jdbcTemplate.queryForObject(
                "SELECT is_nullable = 'NO' "
                        + "FROM information_schema.columns "
                        + "WHERE table_name = 'room' AND column_name = 'room_type_id'",
                Boolean.class);

        assertTrue(Boolean.TRUE.equals(roomTypeIsRequired));
    }

    /** Verifies Flyway seeds exactly the approved read-only RoomType reference records. */
    @Test
    void approvedRoomTypesAreSeededWithoutFixedPrices() {
        Integer approvedRoomTypeCount = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) "
                        + "FROM room_type "
                        + "WHERE (code = 'SINGLE' AND name = 'Single Room' AND capacity = 1) "
                        + "OR (code = 'DOUBLE' AND name = 'Double Room' AND capacity = 2) "
                        + "OR (code = 'TWIN' AND name = 'Twin Room' AND capacity = 2) "
                        + "OR (code = 'TRIPLE' AND name = 'Triple Room' AND capacity = 3) "
                        + "OR (code = 'FAMILY' AND name = 'Family Room' AND capacity = 4)",
                Integer.class);
        Integer roomTypesWithFixedPrice = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) "
                        + "FROM room_type "
                        + "WHERE code IN ('SINGLE', 'DOUBLE', 'TWIN', 'TRIPLE', 'FAMILY') "
                        + "AND base_price IS NOT NULL",
                Integer.class);

        assertEquals(5, approvedRoomTypeCount);
        assertEquals(0, roomTypesWithFixedPrice);
    }

    /** Verifies the Charge v1 migration creates its mandatory Stay relationship and validation constraints. */
    @Test
    void chargeSchemaMatchesV1Rules() {
        Boolean stayIdIsRequired = jdbcTemplate.queryForObject(
                "SELECT is_nullable = 'NO' "
                        + "FROM information_schema.columns "
                        + "WHERE table_name = 'charge' AND column_name = 'stay_id'",
                Boolean.class);
        Boolean amountIsRequired = jdbcTemplate.queryForObject(
                "SELECT is_nullable = 'NO' "
                        + "FROM information_schema.columns "
                        + "WHERE table_name = 'charge' AND column_name = 'amount'",
                Boolean.class);

        assertTrue(Boolean.TRUE.equals(stayIdIsRequired));
        assertTrue(Boolean.TRUE.equals(amountIsRequired));
    }

    /** Verifies the Payment v1 migration creates the mandatory Stay relationship and nullable paid time. */
    @Test
    void paymentSchemaMatchesV1Rules() {
        Boolean stayIdIsRequired = jdbcTemplate.queryForObject(
                "SELECT is_nullable = 'NO' "
                        + "FROM information_schema.columns "
                        + "WHERE table_name = 'payment' AND column_name = 'stay_id'",
                Boolean.class);
        Boolean paidAtIsNullable = jdbcTemplate.queryForObject(
                "SELECT is_nullable = 'YES' "
                        + "FROM information_schema.columns "
                        + "WHERE table_name = 'payment' AND column_name = 'paid_at'",
                Boolean.class);

        assertTrue(Boolean.TRUE.equals(stayIdIsRequired));
        assertTrue(Boolean.TRUE.equals(paidAtIsNullable));
    }

    /** Verifies the V13 Payment currency migration adds the expected columns with correct nullability. */
    @Test
    void paymentCurrencySchemaMatchesV13Rules() {
        Boolean currencyIsRequired = jdbcTemplate.queryForObject(
                "SELECT is_nullable = 'NO' "
                        + "FROM information_schema.columns "
                        + "WHERE table_name = 'payment' AND column_name = 'currency'",
                Boolean.class);
        Boolean appliedAmountIsRequired = jdbcTemplate.queryForObject(
                "SELECT is_nullable = 'NO' "
                        + "FROM information_schema.columns "
                        + "WHERE table_name = 'payment' AND column_name = 'applied_amount'",
                Boolean.class);
        Boolean exchangeRateIsNullable = jdbcTemplate.queryForObject(
                "SELECT is_nullable = 'YES' "
                        + "FROM information_schema.columns "
                        + "WHERE table_name = 'payment' AND column_name = 'exchange_rate'",
                Boolean.class);

        assertTrue(Boolean.TRUE.equals(currencyIsRequired));
        assertTrue(Boolean.TRUE.equals(appliedAmountIsRequired));
        assertTrue(Boolean.TRUE.equals(exchangeRateIsNullable));
    }

    /** Verifies the Expense v1 migration creates required fields and exactly the approved categories. */
    @Test
    void expenseSchemaMatchesV1Rules() {
        Boolean categoryIsRequired = jdbcTemplate.queryForObject(
                "SELECT is_nullable = 'NO' "
                        + "FROM information_schema.columns "
                        + "WHERE table_name = 'expense' AND column_name = 'category_id'",
                Boolean.class);
        Boolean expenseDateIsRequired = jdbcTemplate.queryForObject(
                "SELECT is_nullable = 'NO' "
                        + "FROM information_schema.columns "
                        + "WHERE table_name = 'expense' AND column_name = 'expense_date'",
                Boolean.class);
        String currencyDataType = jdbcTemplate.queryForObject(
                "SELECT data_type "
                        + "FROM information_schema.columns "
                        + "WHERE table_name = 'expense' AND column_name = 'currency'",
                String.class);
        Integer approvedCategoryCount = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) "
                        + "FROM expense_category "
                        + "WHERE code IN ("
                        + "'ELECTRICITY', 'WATER', 'INTERNET', 'SALARY', 'LAUNDRY', "
                        + "'CLEANING', 'SUPPLIES', 'MAINTENANCE', 'OTHER', "
                        + "'REPAIR', 'CONSTRUCTION', 'OTA_COMMISSION' )",
                Integer.class);
        Integer totalCategoryCount = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM expense_category", Integer.class);

        assertTrue(Boolean.TRUE.equals(categoryIsRequired));
        assertTrue(Boolean.TRUE.equals(expenseDateIsRequired));
        assertEquals("character varying", currencyDataType);
        assertEquals(12, approvedCategoryCount);
        assertEquals(12, totalCategoryCount);
    }

    /** Verifies that JPA lifecycle callbacks preserve creator audit fields and refresh updater audit fields. */
    @Test
    @Transactional
    void guestAuditFieldsAreManagedByTheBackend() throws InterruptedException {
        UUID guestId = UUID.randomUUID();
        UUID creatorId = UUID.randomUUID();
        UUID updaterId = UUID.randomUUID();
        Guest guest = Guest.create(
                guestId,
                "G999999",
                "Created",
                "Guest",
                null,
                null,
                null,
                null,
                null);
        guest.audit(creatorId);
        guestRepository.saveAndFlush(guest);

        Boolean creationAuditMatches = jdbcTemplate.queryForObject(
                "SELECT created_by = ? AND updated_by = ? AND created_at = updated_at FROM guest WHERE id = ?",
                Boolean.class,
                creatorId,
                creatorId,
                guestId);
        assertTrue(Boolean.TRUE.equals(creationAuditMatches));

        Thread.sleep(2);
        guest.updateProfile("Updated", "Guest", null, null, null, null, null);
        guest.audit(updaterId);
        guestRepository.saveAndFlush(guest);

        Boolean updateAuditMatches = jdbcTemplate.queryForObject(
                "SELECT created_by = ? AND updated_by = ? AND created_at < updated_at FROM guest WHERE id = ?",
                Boolean.class,
                creatorId,
                updaterId,
                guestId);
        assertTrue(Boolean.TRUE.equals(updateAuditMatches));
    }

    /**
     * Verifies the exact permissions assigned to one seeded role.
     *
     * @param roleCode code of the seeded role
     * @param expectedPermissionCodes permission codes required by the specification
     */
    private void assertPermissionCodes(String roleCode, Set<String> expectedPermissionCodes) {
        Role role = roleRepository.findByCode(roleCode).orElseThrow();
        Set<String> permissionCodes = role.getPermissions().stream()
                .map(permission -> permission.getCode())
                .collect(java.util.stream.Collectors.toSet());
        assertEquals(expectedPermissionCodes, permissionCodes);
    }

    /**
     * Verifies the number of persisted rows for a role and permission relationship.
     *
     * @param roleCode code of the seeded role
     * @param permissionCode code of the permission to inspect
     * @param expectedCount expected relationship-row count
     */
    private void assertRolePermissionRelationshipCount(
            String roleCode, String permissionCode, int expectedCount) {
        Integer relationshipCount = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) "
                        + "FROM role_permission rp "
                        + "JOIN role r ON r.id = rp.role_id "
                        + "JOIN permission p ON p.id = rp.permission_id "
                        + "WHERE r.code = ? AND p.code = ?",
                Integer.class,
                roleCode,
                permissionCode);
        assertEquals(expectedCount, relationshipCount);
    }
}
