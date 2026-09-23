package com.example.hotel;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.UUID;
import javax.sql.DataSource;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.SimpleDriverDataSource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/** Verifies V16 applies cleanly after the complete V1-V15 migration history. */
@Testcontainers(disabledWithoutDocker = true)
class AdditionalRevenueV16MigrationIntegrationTest {

    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");

    @Test
    void shouldApplyV16AfterV1ThroughV15WithoutChangingExistingPermissions() {
        DataSource dataSource = dataSource();
        migrate(dataSource, "15");
        JdbcTemplate jdbcTemplate = new JdbcTemplate(dataSource);

        assertEquals(15, jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM flyway_schema_history WHERE success", Integer.class));
        assertEquals(
                "MANAGE_GUEST",
                jdbcTemplate.queryForObject(
                        "SELECT code FROM permission WHERE id = '00000000-0000-0000-0000-000000000111'",
                        String.class));

        migrate(dataSource, "16");

        assertEquals(16, jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM flyway_schema_history WHERE success", Integer.class));
        assertEquals(
                UUID.fromString("00000000-0000-0000-0000-000000000112"),
                jdbcTemplate.queryForObject(
                        "SELECT id FROM permission WHERE code = 'MANAGE_ADDITIONAL_REVENUE'", UUID.class));
        assertEquals(1, jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM permission WHERE code = 'MANAGE_ADDITIONAL_REVENUE'", Integer.class));
        assertEquals(1, rolePermissionCount(jdbcTemplate, "ADMIN", "MANAGE_ADDITIONAL_REVENUE"));
        assertEquals(1, rolePermissionCount(jdbcTemplate, "MANAGER", "MANAGE_ADDITIONAL_REVENUE"));
        assertEquals(0, rolePermissionCount(jdbcTemplate, "STAFF", "MANAGE_ADDITIONAL_REVENUE"));
        assertEquals(2, jdbcTemplate.queryForObject("SELECT COUNT(*) FROM additional_revenue_category", Integer.class));
        assertEquals(2, jdbcTemplate.queryForObject(
                "SELECT COUNT(DISTINCT id) FROM additional_revenue_category", Integer.class));

        migrate(dataSource, null);
        assertEquals(41, jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM flyway_schema_history WHERE success", Integer.class));
        assertEquals("character varying", jdbcTemplate.queryForObject(
                "SELECT data_type FROM information_schema.columns "
                        + "WHERE table_name = 'additional_revenue' AND column_name = 'currency'",
                String.class));
    }

    private int rolePermissionCount(JdbcTemplate jdbcTemplate, String roleCode, String permissionCode) {
        return jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM role_permission rp "
                        + "JOIN role r ON r.id = rp.role_id "
                        + "JOIN permission p ON p.id = rp.permission_id "
                        + "WHERE r.code = ? AND p.code = ?",
                Integer.class,
                roleCode,
                permissionCode);
    }

    private DataSource dataSource() {
        SimpleDriverDataSource dataSource = new SimpleDriverDataSource();
        dataSource.setDriverClass(org.postgresql.Driver.class);
        dataSource.setUrl(postgres.getJdbcUrl());
        dataSource.setUsername(postgres.getUsername());
        dataSource.setPassword(postgres.getPassword());
        return dataSource;
    }

    private void migrate(DataSource dataSource, String targetVersion) {
        var configuration = Flyway.configure().dataSource(dataSource);
        if (targetVersion != null) {
            configuration = configuration.target(targetVersion);
        }
        configuration.load().migrate();
    }
}
