package com.example.hotel;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.example.hotel.entity.common.Role;
import com.example.hotel.repository.common.RoleRepository;
import java.util.Set;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
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

    /** Xác nhận không còn migration chờ và các migration permission đã được áp dụng. */
    @Test
    void migrationIsCurrent() {
        assertEquals(0, flyway.info().pending().length);
        assertEquals(3, flyway.info().applied().length);
    }

    /** Xác nhận mapping permission sau migration chỉ bổ sung quyền xem reservation cho ADMIN và MANAGER. */
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
                        "VIEW_BOOKING"));
        assertPermissionCodes(
                "MANAGER",
                Set.of(
                        "MANAGE_ROOM",
                        "MANAGE_BOOKING",
                        "MANAGE_PAYMENT",
                        "VIEW_REPORT",
                        "MANAGE_GUEST",
                        "VIEW_BOOKING"));
        assertPermissionCodes("STAFF", Set.of("VIEW_BOOKING", "CHECK_IN", "CHECK_OUT"));
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
}
