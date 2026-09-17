package com.example.hotel.config;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.SimpleDriverDataSource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/** Verifies deterministic, valid, and idempotent Additional Revenue demo data. */
@Testcontainers(disabledWithoutDocker = true)
class DemoDataSeederAdditionalRevenueIntegrationTest {

    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");

    @Test
    void shouldSeedTheApprovedAdditionalRevenueDistribution() {
        JdbcTemplate jdbcTemplate = migratedJdbcTemplate();
        seed(jdbcTemplate);

        assertEquals(36, count(jdbcTemplate, "SELECT COUNT(*) FROM additional_revenue"));
        assertEquals(12, count(jdbcTemplate,
                "SELECT COUNT(DISTINCT EXTRACT(MONTH FROM revenue_date)) FROM additional_revenue WHERE EXTRACT(YEAR FROM revenue_date) = 2026"));
        assertEquals(0, count(jdbcTemplate,
                "SELECT COUNT(*) FROM (SELECT EXTRACT(MONTH FROM revenue_date) month, COUNT(*) count FROM additional_revenue GROUP BY EXTRACT(MONTH FROM revenue_date)) grouped WHERE count <> 3"));
        assertEquals(0, count(jdbcTemplate,
                "SELECT COUNT(*) FROM additional_revenue WHERE EXTRACT(YEAR FROM revenue_date) <> 2026"));

        assertEquals(28, count(jdbcTemplate, "SELECT COUNT(*) FROM additional_revenue r JOIN additional_revenue_category c ON c.id = r.category_id WHERE c.code = 'ELECTRIC_CART_RENTAL'"));
        assertEquals(8, count(jdbcTemplate, "SELECT COUNT(*) FROM additional_revenue r JOIN additional_revenue_category c ON c.id = r.category_id WHERE c.code = 'OTHER'"));
        assertEquals(0, count(jdbcTemplate, "SELECT COUNT(*) FROM additional_revenue_category WHERE code NOT IN ('ELECTRIC_CART_RENTAL', 'OTHER')"));

        assertEquals(32, count(jdbcTemplate, "SELECT COUNT(*) FROM additional_revenue WHERE status = 'RECORDED'"));
        assertEquals(4, count(jdbcTemplate, "SELECT COUNT(*) FROM additional_revenue WHERE status = 'VOIDED'"));
        assertEquals(0, count(jdbcTemplate, "SELECT COUNT(*) FROM additional_revenue WHERE status = 'VOIDED' AND (void_reason IS NULL OR voided_at IS NULL OR voided_by IS NULL)"));
        assertEquals(0, count(jdbcTemplate, "SELECT COUNT(*) FROM additional_revenue WHERE status = 'RECORDED' AND (void_reason IS NOT NULL OR voided_at IS NOT NULL OR voided_by IS NOT NULL)"));
        assertEquals(4, count(jdbcTemplate, "SELECT COUNT(DISTINCT EXTRACT(MONTH FROM revenue_date)) FROM additional_revenue WHERE status = 'VOIDED'"));

        assertEquals(24, count(jdbcTemplate, "SELECT COUNT(*) FROM additional_revenue WHERE payment_method = 'CASH'"));
        assertEquals(7, count(jdbcTemplate, "SELECT COUNT(*) FROM additional_revenue WHERE payment_method = 'BANK_TRANSFER'"));
        assertEquals(3, count(jdbcTemplate, "SELECT COUNT(*) FROM additional_revenue WHERE payment_method = 'CREDIT_CARD'"));
        assertEquals(2, count(jdbcTemplate, "SELECT COUNT(*) FROM additional_revenue WHERE payment_method = 'OTHER'"));
        assertEquals(0, count(jdbcTemplate, "SELECT COUNT(*) FROM additional_revenue WHERE currency <> 'VND' OR amount <= 0 OR amount <> trunc(amount)"));
        assertEquals(0, count(jdbcTemplate, "SELECT COUNT(*) FROM additional_revenue r JOIN additional_revenue_category c ON c.id = r.category_id WHERE c.code = 'ELECTRIC_CART_RENTAL' AND (r.amount < 100000 OR r.amount > 500000)"));
        assertEquals(0, count(jdbcTemplate, "SELECT COUNT(*) FROM additional_revenue r JOIN additional_revenue_category c ON c.id = r.category_id WHERE c.code = 'OTHER' AND (r.amount < 50000 OR r.amount > 1000000)"));
        assertEquals(96, count(jdbcTemplate, "SELECT COUNT(*) FROM expense"));
    }

    @Test
    void shouldNotDuplicateAdditionalRevenueOnRepeatedSeeding() {
        JdbcTemplate jdbcTemplate = migratedJdbcTemplate();
        seed(jdbcTemplate);
        seed(jdbcTemplate);

        assertEquals(36, count(jdbcTemplate, "SELECT COUNT(*) FROM additional_revenue"));
        assertEquals(96, count(jdbcTemplate, "SELECT COUNT(*) FROM expense"));
    }

    private void seed(JdbcTemplate jdbcTemplate) {
        new DemoDataSeeder().seed(jdbcTemplate,
                Clock.fixed(Instant.parse("2026-09-15T00:00:00Z"), ZoneId.of("Asia/Ho_Chi_Minh")));
    }

    private int count(JdbcTemplate jdbcTemplate, String sql) {
        return jdbcTemplate.queryForObject(sql, Integer.class);
    }

    private JdbcTemplate migratedJdbcTemplate() {
        SimpleDriverDataSource dataSource = new SimpleDriverDataSource();
        dataSource.setDriverClass(org.postgresql.Driver.class);
        dataSource.setUrl(postgres.getJdbcUrl());
        dataSource.setUsername(postgres.getUsername());
        dataSource.setPassword(postgres.getPassword());
        Flyway.configure().dataSource(dataSource).load().migrate();
        return new JdbcTemplate(dataSource);
    }
}
