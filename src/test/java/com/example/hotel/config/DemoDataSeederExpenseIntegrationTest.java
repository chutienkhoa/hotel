package com.example.hotel.config;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;
import java.util.Set;
import javax.sql.DataSource;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.SimpleDriverDataSource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * Verifies the dev seeder's deterministic Expense demo data: volume, month/category coverage,
 * VND-only currency, lifecycle/approver consistency, referential integrity, and idempotency.
 */
@Testcontainers(disabledWithoutDocker = true)
class DemoDataSeederExpenseIntegrationTest {

    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");

    private static final Set<String> SUPPORTED_STATUSES =
            Set.of("DRAFT", "SUBMITTED", "APPROVED", "REJECTED", "POSTED");

    /** Confirms roughly 80-100 Expense rows are seeded, spanning every month with the recurring categories. */
    @Test
    void shouldSeedExpectedExpenseVolumeAndMonthlyCoverage() {
        JdbcTemplate jdbcTemplate = migratedJdbcTemplate();
        Clock clock = Clock.fixed(Instant.parse("2026-09-15T00:00:00Z"), ZoneId.of("Asia/Ho_Chi_Minh"));

        new DemoDataSeeder().seed(jdbcTemplate, clock);

        Integer totalExpenses = jdbcTemplate.queryForObject("SELECT COUNT(*) FROM expense", Integer.class);
        assertTrue(totalExpenses != null && totalExpenses >= 80 && totalExpenses <= 100,
                "Expected 80-100 seeded Expenses, found " + totalExpenses);

        Integer monthsCovered = jdbcTemplate.queryForObject(
                "SELECT COUNT(DISTINCT EXTRACT(MONTH FROM expense_date)) FROM expense", Integer.class);
        assertEquals(12, monthsCovered);

        for (String recurringCategory : List.of("SALARY", "ELECTRICITY", "WATER", "INTERNET")) {
            Integer monthsWithCategory = jdbcTemplate.queryForObject(
                    "SELECT COUNT(DISTINCT EXTRACT(MONTH FROM e.expense_date)) "
                            + "FROM expense e JOIN expense_category c ON c.id = e.category_id "
                            + "WHERE c.code = ?",
                    Integer.class, recurringCategory);
            assertEquals(12, monthsWithCategory, recurringCategory + " must appear in every month");
        }

        for (String occasionalCategory : List.of("MAINTENANCE", "REPAIR", "CONSTRUCTION", "OTA_COMMISSION")) {
            Integer occurrences = jdbcTemplate.queryForObject(
                    "SELECT COUNT(*) FROM expense e JOIN expense_category c ON c.id = e.category_id "
                            + "WHERE c.code = ?",
                    Integer.class, occasionalCategory);
            assertTrue(occurrences != null && occurrences > 0, occasionalCategory + " must be seeded at least once");
        }
    }

    /** Confirms every seeded Expense is VND, positive, references a real category, and uses a supported status. */
    @Test
    void shouldSeedOnlyValidVndExpensesWithSupportedStatusAndRealCategory() {
        JdbcTemplate jdbcTemplate = migratedJdbcTemplate();
        Clock clock = Clock.fixed(Instant.parse("2026-09-15T00:00:00Z"), ZoneId.of("Asia/Ho_Chi_Minh"));

        new DemoDataSeeder().seed(jdbcTemplate, clock);

        Integer nonVndCount = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM expense WHERE currency <> 'VND'", Integer.class);
        assertEquals(0, nonVndCount);

        Integer nonPositiveCount = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM expense WHERE amount <= 0", Integer.class);
        assertEquals(0, nonPositiveCount);

        Integer orphanCategoryCount = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM expense e "
                        + "LEFT JOIN expense_category c ON c.id = e.category_id WHERE c.id IS NULL",
                Integer.class);
        assertEquals(0, orphanCategoryCount);

        List<String> statuses = jdbcTemplate.queryForList("SELECT DISTINCT status FROM expense", String.class);
        for (String status : statuses) {
            assertTrue(SUPPORTED_STATUSES.contains(status), "Unsupported seeded status: " + status);
        }
    }

    /** Confirms lifecycle/approver consistency: only APPROVED/POSTED rows carry an approver. */
    @Test
    void shouldKeepApprovedByConsistentWithLifecycleStatus() {
        JdbcTemplate jdbcTemplate = migratedJdbcTemplate();
        Clock clock = Clock.fixed(Instant.parse("2026-09-15T00:00:00Z"), ZoneId.of("Asia/Ho_Chi_Minh"));

        new DemoDataSeeder().seed(jdbcTemplate, clock);

        Integer approvedWithoutApprover = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM expense WHERE status IN ('APPROVED', 'POSTED') AND approved_by IS NULL",
                Integer.class);
        assertEquals(0, approvedWithoutApprover);

        Integer unapprovedWithApprover = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM expense "
                        + "WHERE status IN ('DRAFT', 'SUBMITTED', 'REJECTED') AND approved_by IS NOT NULL",
                Integer.class);
        assertEquals(0, unapprovedWithApprover);

        Integer approvedByMissingUser = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM expense e "
                        + "LEFT JOIN app_user u ON u.id = e.approved_by "
                        + "WHERE e.approved_by IS NOT NULL AND u.id IS NULL",
                Integer.class);
        assertEquals(0, approvedByMissingUser);
    }

    /** Confirms every seeded Expense has required audit fields populated with a real, valid user. */
    @Test
    void shouldPopulateAuditFieldsWithValidUser() {
        JdbcTemplate jdbcTemplate = migratedJdbcTemplate();
        Clock clock = Clock.fixed(Instant.parse("2026-09-15T00:00:00Z"), ZoneId.of("Asia/Ho_Chi_Minh"));

        new DemoDataSeeder().seed(jdbcTemplate, clock);

        Integer missingAuditFields = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM expense "
                        + "WHERE created_at IS NULL OR created_by IS NULL OR updated_at IS NULL OR updated_by IS NULL",
                Integer.class);
        assertEquals(0, missingAuditFields);

        Integer auditUserCount = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM app_user WHERE username = 'demo-data-seeder'", Integer.class);
        assertEquals(1, auditUserCount);
    }

    /** Confirms seeded Expense ids/amounts are stable and deterministic across independent seed runs. */
    @Test
    void shouldProduceDeterministicExpenseDataAcrossIndependentDatabases() {
        Clock clock = Clock.fixed(Instant.parse("2026-09-15T00:00:00Z"), ZoneId.of("Asia/Ho_Chi_Minh"));

        JdbcTemplate first = migratedJdbcTemplate();
        new DemoDataSeeder().seed(first, clock);
        List<Map<String, Object>> firstRows = first.queryForList(
                "SELECT id, amount, expense_date, status FROM expense ORDER BY id");

        PostgreSQLContainer<?> secondContainer = new PostgreSQLContainer<>("postgres:16-alpine");
        secondContainer.start();
        try {
            DataSource secondDataSource = dataSourceFor(secondContainer);
            Flyway.configure().dataSource(secondDataSource).load().migrate();
            JdbcTemplate second = new JdbcTemplate(secondDataSource);
            new DemoDataSeeder().seed(second, clock);
            List<Map<String, Object>> secondRows = second.queryForList(
                    "SELECT id, amount, expense_date, status FROM expense ORDER BY id");

            assertEquals(firstRows.size(), secondRows.size());
            for (int index = 0; index < firstRows.size(); index++) {
                assertEquals(firstRows.get(index).get("id"), secondRows.get(index).get("id"));
                assertEquals(
                        0,
                        ((BigDecimal) firstRows.get(index).get("amount"))
                                .compareTo((BigDecimal) secondRows.get(index).get("amount")));
                assertEquals(firstRows.get(index).get("expense_date"), secondRows.get(index).get("expense_date"));
                assertEquals(firstRows.get(index).get("status"), secondRows.get(index).get("status"));
            }
        } finally {
            secondContainer.stop();
        }
    }

    /** Confirms re-running the seeder does not create duplicate Expense rows. */
    @Test
    void shouldNotDuplicateExpensesOnRepeatedSeeding() {
        JdbcTemplate jdbcTemplate = migratedJdbcTemplate();
        Clock clock = Clock.fixed(Instant.parse("2026-09-15T00:00:00Z"), ZoneId.of("Asia/Ho_Chi_Minh"));
        DemoDataSeeder seeder = new DemoDataSeeder();

        seeder.seed(jdbcTemplate, clock);
        Integer firstRunCount = jdbcTemplate.queryForObject("SELECT COUNT(*) FROM expense", Integer.class);

        seeder.seed(jdbcTemplate, clock);
        Integer secondRunCount = jdbcTemplate.queryForObject("SELECT COUNT(*) FROM expense", Integer.class);

        assertEquals(firstRunCount, secondRunCount);
    }

    /** Confirms no duplicate/extra Expense category reference rows were introduced by this seeding step. */
    @Test
    void shouldReuseExistingV15CategoriesWithoutDuplication() {
        JdbcTemplate jdbcTemplate = migratedJdbcTemplate();
        Clock clock = Clock.fixed(Instant.parse("2026-09-15T00:00:00Z"), ZoneId.of("Asia/Ho_Chi_Minh"));

        new DemoDataSeeder().seed(jdbcTemplate, clock);

        Integer categoryCount = jdbcTemplate.queryForObject("SELECT COUNT(*) FROM expense_category", Integer.class);
        assertEquals(12, categoryCount);
    }

    /** Migrates a fresh Testcontainers Postgres instance and returns a plain JDBC template for it. */
    private JdbcTemplate migratedJdbcTemplate() {
        DataSource dataSource = dataSourceFor(postgres);
        Flyway.configure().dataSource(dataSource).load().migrate();
        return new JdbcTemplate(dataSource);
    }

    /** Builds a plain JDBC data source for the supplied Testcontainers PostgreSQL instance. */
    private DataSource dataSourceFor(PostgreSQLContainer<?> container) {
        SimpleDriverDataSource dataSource = new SimpleDriverDataSource();
        dataSource.setDriverClass(org.postgresql.Driver.class);
        dataSource.setUrl(container.getJdbcUrl());
        dataSource.setUsername(container.getUsername());
        dataSource.setPassword(container.getPassword());
        return dataSource;
    }
}
