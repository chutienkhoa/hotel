package com.example.hotel;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.example.hotel.dto.common.request.RolePermissionUpdateRequest;
import com.example.hotel.repository.common.RoleRepository;
import com.example.hotel.security.CurrentUser;
import com.example.hotel.service.common.RolePermissionService;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/** Verifies the real JPA/PostgreSQL permission-matrix save path, its locking query, and concurrent saves. */
@SpringBootTest
@Testcontainers(disabledWithoutDocker = true)
class RolePermissionIntegrationTest {

    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");

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

    @Autowired
    RolePermissionService service;

    @Autowired
    RoleRepository roleRepository;

    @Autowired
    JdbcTemplate jdbc;

    private UUID actorId;

    /** Creates an audit actor and authenticates as it; restores the seeded matrix for isolation. */
    @BeforeEach
    void setUp() {
        actorId = UUID.randomUUID();
        jdbc.update("INSERT INTO app_user (id, username, password_hash, active, created_at, updated_at) "
                + "VALUES (?, ?, 'hash', TRUE, now(), now())", actorId, "rp." + actorId);
        jdbc.update("DELETE FROM audit_log WHERE entity_type = 'ROLE'");
        jdbc.update("DELETE FROM role_permission WHERE role_id IN (SELECT id FROM role WHERE code = 'MANAGER') "
                + "AND permission_id IN (SELECT id FROM permission WHERE code = 'VIEW_REPORT')");
        jdbc.update("INSERT INTO role_permission (role_id, permission_id) SELECT r.id, p.id FROM role r, permission p "
                + "WHERE r.code = 'MANAGER' AND p.code = 'VIEW_REPORT'");
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(new CurrentUser(actorId, "admin"), null));
    }

    /** Confirms the lock query is valid PostgreSQL and returns the three built-in roles. */
    @Test
    @org.springframework.transaction.annotation.Transactional
    void lockQueryReturnsBuiltInRoles() {
        assertEquals(3, roleRepository.lockBuiltInRoleIds().size());
    }

    /** Confirms migration V29 seeded MANAGE_HOUSEKEEPING for ADMIN and MANAGER only, not for STAFF. */
    @Test
    void shouldSeedManageHousekeepingForAdministrativeRolesOnly() {
        assertEquals(1, jdbc.queryForObject(
                "SELECT COUNT(*) FROM permission WHERE code = 'MANAGE_HOUSEKEEPING'", Integer.class));
        assertEquals(1, count("ADMIN", "MANAGE_HOUSEKEEPING"));
        assertEquals(1, count("MANAGER", "MANAGE_HOUSEKEEPING"));
        assertEquals(0, count("STAFF", "MANAGE_HOUSEKEEPING"));
    }

    /** Confirms a real save changes role_permission, audits once with sorted values, and leaves other roles alone. */
    @Test
    void shouldPersistChangeAndAuditOnlyChangedRole() {
        service.update(request(allExposed(), without("MANAGE_USER", "VIEW_REPORT"), staffDefaults()));

        assertEquals(0, count("MANAGER", "VIEW_REPORT"));
        assertEquals(1, count("ADMIN", "MANAGE_USER"));
        assertEquals(0, count("MANAGER", "MANAGE_USER"));
        assertEquals(0, jdbc.queryForObject(
                "SELECT COUNT(*) FROM role_permission rp JOIN permission p ON p.id = rp.permission_id "
                        + "WHERE p.code = 'DELETE_RESERVATION'", Integer.class));
        List<Map<String, Object>> audits = jdbc.queryForList(
                "SELECT action, entity_type, entity_id, old_value, new_value, user_id FROM audit_log "
                        + "WHERE entity_type = 'ROLE'");
        assertEquals(1, audits.size());
        assertEquals("ROLE_PERMISSION_CHANGE", audits.get(0).get("action"));
        assertEquals(actorId, audits.get(0).get("user_id"));
        assertTrue(((String) audits.get(0).get("old_value")).contains("VIEW_REPORT"));
        assertFalse(((String) audits.get(0).get("new_value")).contains("VIEW_REPORT"));
    }

    /** Confirms two concurrent saves are serialized and leave one consistent final state with no duplicates. */
    @Test
    void concurrentSavesLeaveConsistentState() throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(2);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<?>> results = new ArrayList<>();
        for (List<String> managerGrants : List.of(without("MANAGE_USER", "VIEW_REPORT"), without("MANAGE_USER", "MANAGE_ROOM"))) {
            results.add(pool.submit(() -> {
                SecurityContextHolder.getContext().setAuthentication(
                        new UsernamePasswordAuthenticationToken(new CurrentUser(actorId, "admin"), null));
                start.await();
                service.update(request(allExposed(), managerGrants, staffDefaults()));
                return null;
            }));
        }
        start.countDown();
        for (Future<?> result : results) {
            result.get();
        }
        pool.shutdown();

        boolean firstWon = count("MANAGER", "VIEW_REPORT") == 0 && count("MANAGER", "MANAGE_ROOM") == 1;
        boolean secondWon = count("MANAGER", "VIEW_REPORT") == 1 && count("MANAGER", "MANAGE_ROOM") == 0;
        assertTrue(firstWon || secondWon, "final state must equal exactly one of the two submitted matrices");
        assertEquals(0, jdbc.queryForObject(
                "SELECT COUNT(*) FROM (SELECT role_id, permission_id FROM role_permission "
                        + "GROUP BY role_id, permission_id HAVING COUNT(*) > 1) d", Integer.class));
        assertEquals(1, count("ADMIN", "MANAGE_USER"));
    }

    private int count(String role, String permission) {
        return jdbc.queryForObject(
                "SELECT COUNT(*) FROM role_permission rp JOIN role r ON r.id = rp.role_id "
                        + "JOIN permission p ON p.id = rp.permission_id WHERE r.code = ? AND p.code = ?",
                Integer.class, role, permission);
    }

    private List<String> allExposed() {
        return List.of("VIEW_REPORT", "VIEW_BOOKING", "MANAGE_BOOKING", "CHECK_IN", "CHECK_OUT", "CHANGE_ROOM", "EXTEND_STAY",
                "MANAGE_PAYMENT", "MANAGE_GUEST", "MANAGE_ROOM", "MANAGE_HOUSEKEEPING", "MANAGE_EXPENSE", "MANAGE_ADDITIONAL_REVENUE",
                "MANAGE_STAFF", "MANAGE_ATTENDANCE", "MANAGE_USER");
    }

    private List<String> without(String... excluded) {
        List<String> all = new ArrayList<>(allExposed());
        all.removeAll(Set.of(excluded));
        return all;
    }

    private List<String> staffDefaults() {
        return List.of("VIEW_BOOKING", "CHECK_IN", "CHECK_OUT", "MANAGE_PAYMENT", "CHANGE_ROOM", "EXTEND_STAY");
    }

    private RolePermissionUpdateRequest request(List<String> admin, List<String> manager, List<String> staff) {
        RolePermissionUpdateRequest request = new RolePermissionUpdateRequest();
        request.setSubmittedRoles(new ArrayList<>(List.of("ADMIN", "MANAGER", "STAFF")));
        request.setGrants(Map.of("ADMIN", new ArrayList<>(admin), "MANAGER", new ArrayList<>(manager),
                "STAFF", new ArrayList<>(staff)));
        return request;
    }
}
