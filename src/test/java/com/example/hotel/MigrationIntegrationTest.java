package com.example.hotel;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.example.hotel.entity.common.Role;
import com.example.hotel.entity.customer.Guest;
import com.example.hotel.repository.booking.ReservationRepository;
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
    ReservationRepository reservationRepository;

    @Autowired
    GuestRepository guestRepository;

    @Autowired
    JdbcTemplate jdbcTemplate;

    /** Xác nhận không còn migration chờ và các migration permission đã được áp dụng. */
    @Test
    void migrationIsCurrent() {
        assertEquals(0, flyway.info().pending().length);
        assertEquals(15, flyway.info().applied().length);
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
                        "MANAGE_GUEST",
                        "VIEW_REPORT",
                        "VIEW_BOOKING",
                        "CHECK_OUT"));
        assertPermissionCodes(
                "MANAGER",
                Set.of(
                        "MANAGE_ROOM",
                        "MANAGE_BOOKING",
                        "MANAGE_PAYMENT",
                        "MANAGE_EXPENSE",
                        "VIEW_REPORT",
                        "MANAGE_GUEST",
                        "VIEW_BOOKING",
                        "CHECK_OUT"));
        assertPermissionCodes("STAFF", Set.of("VIEW_BOOKING", "CHECK_IN", "CHECK_OUT"));
        assertRolePermissionRelationshipCount("ADMIN", "VIEW_REPORT", 1);
        assertRolePermissionRelationshipCount("MANAGER", "VIEW_REPORT", 1);
        assertRolePermissionRelationshipCount("STAFF", "VIEW_REPORT", 0);
        assertRolePermissionRelationshipCount("ADMIN", "CHECK_OUT", 1);
        assertRolePermissionRelationshipCount("MANAGER", "CHECK_OUT", 1);
        assertRolePermissionRelationshipCount("STAFF", "CHECK_OUT", 1);
        assertRolePermissionRelationshipCount("ADMIN", "MANAGE_EXPENSE", 1);
        assertRolePermissionRelationshipCount("MANAGER", "MANAGE_EXPENSE", 1);
        assertRolePermissionRelationshipCount("STAFF", "MANAGE_EXPENSE", 0);
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
