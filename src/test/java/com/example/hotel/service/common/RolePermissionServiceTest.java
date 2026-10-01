package com.example.hotel.service.common;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.example.hotel.dto.common.request.RolePermissionUpdateRequest;
import com.example.hotel.dto.common.response.RolePermissionMatrixResponse;
import com.example.hotel.entity.common.AuditLog;
import com.example.hotel.entity.common.Permission;
import com.example.hotel.entity.common.Role;
import com.example.hotel.repository.common.AuditLogRepository;
import com.example.hotel.repository.common.PermissionRepository;
import com.example.hotel.repository.common.RoleRepository;
import com.example.hotel.security.CurrentUser;
import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.springframework.dao.CannotAcquireLockException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.server.ResponseStatusException;

/** Verifies the permission matrix: loading, updating, the MANAGE_USER invariant, validation, locking and audit. */
class RolePermissionServiceTest {

    private static final List<String> EXPOSED = List.of(
            "VIEW_REPORT", "VIEW_BOOKING", "MANAGE_BOOKING", "CHECK_IN", "CHECK_OUT", "CHANGE_ROOM", "EXTEND_STAY",
            "MANAGE_PAYMENT", "MANAGE_GUEST", "MANAGE_ROOM", "MANAGE_HOUSEKEEPING", "MANAGE_EXPENSE", "MANAGE_ADDITIONAL_REVENUE",
            "MANAGE_STAFF", "MANAGE_ATTENDANCE", "MANAGE_USER");

    private final RoleRepository roles = mock(RoleRepository.class);
    private final PermissionRepository permissions = mock(PermissionRepository.class);
    private final AuditLogRepository audits = mock(AuditLogRepository.class);
    private final RolePermissionService service = new RolePermissionService(roles, permissions, audits);
    private final UUID actorId = UUID.randomUUID();
    private final Map<String, Permission> catalogue = new HashMap<>();
    private Role admin;
    private Role manager;
    private Role staff;

    /** Builds the seeded state: the current migration matrix plus the dormant DELETE_RESERVATION. */
    @BeforeEach
    void setUp() throws Exception {
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(new CurrentUser(actorId, "admin"), null));
        for (String code : EXPOSED) {
            catalogue.put(code, permission(code));
        }
        catalogue.put("DELETE_RESERVATION", permission("DELETE_RESERVATION"));
        admin = role("ADMIN", "VIEW_REPORT", "VIEW_BOOKING", "MANAGE_BOOKING", "CHECK_IN", "CHECK_OUT", "CHANGE_ROOM", "EXTEND_STAY",
                "MANAGE_PAYMENT", "MANAGE_GUEST", "MANAGE_ROOM", "MANAGE_HOUSEKEEPING", "MANAGE_EXPENSE", "MANAGE_ADDITIONAL_REVENUE",
                "MANAGE_STAFF", "MANAGE_ATTENDANCE", "MANAGE_USER");
        manager = role("MANAGER", "VIEW_REPORT", "VIEW_BOOKING", "MANAGE_BOOKING", "CHECK_IN", "CHECK_OUT", "CHANGE_ROOM", "EXTEND_STAY",
                "MANAGE_PAYMENT", "MANAGE_GUEST", "MANAGE_ROOM", "MANAGE_HOUSEKEEPING", "MANAGE_EXPENSE", "MANAGE_ADDITIONAL_REVENUE",
                "MANAGE_STAFF", "MANAGE_ATTENDANCE");
        staff = role("STAFF", "VIEW_BOOKING", "CHECK_IN", "CHECK_OUT", "CHANGE_ROOM", "EXTEND_STAY", "MANAGE_PAYMENT");
        when(roles.findByCodeIn(any())).thenReturn(List.of(admin, manager, staff));
        when(permissions.findByCodeIn(any())).thenAnswer(invocation -> {
            List<Permission> found = new ArrayList<>();
            for (String code : (Iterable<String>) invocation.getArgument(0)) {
                if (catalogue.containsKey(code)) {
                    found.add(catalogue.get(code));
                }
            }
            return found;
        });
    }

    /** Clears the authenticated user. */
    @AfterEach
    void clear() {
        SecurityContextHolder.clearContext();
    }

    /** Confirms the matrix reflects the database state, lists only built-in roles, and hides DELETE_RESERVATION. */
    @Test
    void shouldLoadMatrixFromDatabaseState() {
        RolePermissionMatrixResponse matrix = service.loadMatrix();

        assertEquals(List.of("ADMIN", "MANAGER", "STAFF"), matrix.roles());
        Map<String, RolePermissionMatrixResponse.Item> items = new LinkedHashMap<>();
        matrix.groups().forEach(group -> group.items().forEach(item -> items.put(item.code(), item)));
        assertEquals(16, items.size());
        assertTrue(items.get("EXTEND_STAY").granted().get("ADMIN") && items.get("EXTEND_STAY").granted().get("MANAGER")
                && items.get("EXTEND_STAY").granted().get("STAFF"), "EXTEND_STAY is a default of all built-in roles");
        assertFalse(items.get("MANAGE_BOOKING").granted().get("STAFF"), "STAFF never gets MANAGE_BOOKING");
        assertFalse(items.containsKey("DELETE_RESERVATION"));
        assertTrue(items.get("VIEW_REPORT").granted().get("MANAGER"));
        assertFalse(items.get("VIEW_REPORT").granted().get("STAFF"));
        assertTrue(items.get("CHECK_IN").granted().get("STAFF"));
        assertEquals(List.of("Dashboard", "Reservations", "Guests", "Rooms", "Housekeeping", "Finance", "Administration"),
                matrix.groups().stream().map(RolePermissionMatrixResponse.Group::label).toList());
    }

    /** Confirms MANAGE_USER is shown as a locked row: ON for ADMIN, OFF for the others. */
    @Test
    void shouldShowManageUserAsLockedInvariant() {
        var item = service.loadMatrix().groups().stream()
                .flatMap(group -> group.items().stream())
                .filter(candidate -> candidate.code().equals("MANAGE_USER"))
                .findFirst()
                .orElseThrow();

        assertTrue(item.locked());
        assertTrue(item.granted().get("ADMIN"));
        assertFalse(item.granted().get("MANAGER"));
        assertFalse(item.granted().get("STAFF"));
    }

    /** Confirms permissions can be added and removed, and several roles updated in one save. */
    @Test
    void shouldAddRemoveAndUpdateMultipleRoles() {
        RolePermissionUpdateRequest request = request(
                grants("ADMIN", EXPOSED),
                grants("MANAGER", without(EXPOSED, "MANAGE_USER", "MANAGE_EXPENSE")),
                grants("STAFF", "VIEW_BOOKING", "CHECK_IN", "CHECK_OUT", "CHANGE_ROOM", "EXTEND_STAY", "MANAGE_PAYMENT", "MANAGE_GUEST"));

        service.update(request);

        assertFalse(codes(manager).contains("MANAGE_EXPENSE"));
        assertTrue(codes(staff).contains("MANAGE_GUEST"));
        verify(audits, org.mockito.Mockito.times(2)).save(any(AuditLog.class));
    }

    /** Confirms an unchanged role produces no audit entry and no save. */
    @Test
    void shouldNotAuditUnchangedRoles() {
        service.update(request(grants("ADMIN", EXPOSED), grants("MANAGER", without(EXPOSED, "MANAGE_USER")),
                grants("STAFF", "VIEW_BOOKING", "CHECK_IN", "CHECK_OUT", "CHANGE_ROOM", "EXTEND_STAY", "MANAGE_PAYMENT")));

        verify(audits, never()).save(any());
        verify(roles, never()).saveAndFlush(any());
    }

    /** Confirms ADMIN keeps MANAGE_USER even when the submission omits it (a disabled/locked checkbox). */
    @Test
    void shouldAlwaysRetainManageUserOnAdmin() {
        service.update(request(grants("ADMIN", without(EXPOSED, "MANAGE_USER", "VIEW_REPORT")),
                grants("MANAGER", without(EXPOSED, "MANAGE_USER")),
                grants("STAFF", "VIEW_BOOKING", "CHECK_IN", "CHECK_OUT", "CHANGE_ROOM", "EXTEND_STAY", "MANAGE_PAYMENT")));

        assertTrue(codes(admin).contains("MANAGE_USER"));
        assertFalse(codes(admin).contains("VIEW_REPORT"));
    }

    /** Confirms a forged request cannot grant MANAGE_USER to MANAGER or STAFF, and nothing is changed. */
    @Test
    void shouldRejectManageUserForNonAdminRoles() {
        for (String role : List.of("MANAGER", "STAFF")) {
            RolePermissionUpdateRequest request = validRequest();
            request.getGrants().put(role, new ArrayList<>(List.of("VIEW_BOOKING", "MANAGE_USER")));

            assertThrows(ResponseStatusException.class, () -> service.update(request));
        }
        assertFalse(codes(manager).contains("MANAGE_USER"));
        assertFalse(codes(staff).contains("MANAGE_USER"));
        verify(roles, never()).saveAndFlush(any());
    }

    /** Confirms MANAGE_USER already wrongly present on a non-ADMIN role is removed by a save. */
    @Test
    void shouldRepairManageUserDriftOnNonAdminRole() {
        manager.getPermissions().add(catalogue.get("MANAGE_USER"));

        service.update(request(grants("ADMIN", EXPOSED), grants("MANAGER", without(EXPOSED, "MANAGE_USER")),
                grants("STAFF", "VIEW_BOOKING", "CHECK_IN", "CHECK_OUT", "CHANGE_ROOM", "EXTEND_STAY", "MANAGE_PAYMENT")));

        assertFalse(codes(manager).contains("MANAGE_USER"));
    }

    /** Confirms unknown, dormant and malformed submissions are rejected without any change. */
    @Test
    void shouldRejectUnknownDormantAndMalformedSubmissions() {
        for (String forged : List.of("SUPER_POWER", "DELETE_RESERVATION", "PERM_VIEW_REPORT", "")) {
            RolePermissionUpdateRequest request = validRequest();
            request.getGrants().put("MANAGER", new ArrayList<>(List.of("VIEW_BOOKING", forged)));
            assertThrows(ResponseStatusException.class, () -> service.update(request));
        }
        RolePermissionUpdateRequest unknownRole = validRequest();
        unknownRole.getGrants().put("SUPERUSER", new ArrayList<>(List.of("VIEW_BOOKING")));
        assertThrows(ResponseStatusException.class, () -> service.update(unknownRole));

        RolePermissionUpdateRequest missingRole = validRequest();
        missingRole.setSubmittedRoles(new ArrayList<>(List.of("ADMIN", "MANAGER")));
        assertThrows(ResponseStatusException.class, () -> service.update(missingRole));

        RolePermissionUpdateRequest extraRole = validRequest();
        extraRole.setSubmittedRoles(new ArrayList<>(List.of("ADMIN", "MANAGER", "STAFF", "GUEST")));
        assertThrows(ResponseStatusException.class, () -> service.update(extraRole));

        assertThrows(ResponseStatusException.class, () -> service.update(new RolePermissionUpdateRequest()));
        verify(roles, never()).saveAndFlush(any());
        verify(audits, never()).save(any());
    }

    /** Confirms the dormant DELETE_RESERVATION never becomes granted and a held non-exposed permission is preserved. */
    @Test
    void shouldNeverGrantDormantPermissionAndPreserveNonExposedOnes() {
        service.update(request(grants("ADMIN", EXPOSED), grants("MANAGER", without(EXPOSED, "MANAGE_USER", "VIEW_REPORT")),
                grants("STAFF", "VIEW_BOOKING", "CHECK_IN", "CHECK_OUT", "CHANGE_ROOM", "EXTEND_STAY", "MANAGE_PAYMENT")));
        assertFalse(codes(manager).contains("DELETE_RESERVATION"));

        staff.getPermissions().add(catalogue.get("DELETE_RESERVATION"));
        service.update(request(grants("ADMIN", EXPOSED), grants("MANAGER", without(EXPOSED, "MANAGE_USER", "VIEW_REPORT")),
                grants("STAFF", "VIEW_BOOKING", "CHECK_IN", "CHECK_OUT", "CHANGE_ROOM", "EXTEND_STAY", "MANAGE_PAYMENT", "MANAGE_ROOM")));
        assertTrue(codes(staff).contains("DELETE_RESERVATION"));
        assertTrue(codes(staff).contains("MANAGE_ROOM"));
    }

    /** Confirms ADMIN may remove its own configurable permissions, and they are not silently restored. */
    @Test
    void shouldAllowAdminToRemoveOwnConfigurablePermission() {
        service.update(request(grants("ADMIN", without(EXPOSED, "VIEW_REPORT")),
                grants("MANAGER", without(EXPOSED, "MANAGE_USER")),
                grants("STAFF", "VIEW_BOOKING", "CHECK_IN", "CHECK_OUT", "CHANGE_ROOM", "EXTEND_STAY", "MANAGE_PAYMENT")));

        assertFalse(codes(admin).contains("VIEW_REPORT"));
        assertTrue(codes(admin).contains("MANAGE_USER"));
    }

    /** Confirms one ROLE_PERMISSION_CHANGE audit per changed role, with role id, actor and deterministic values. */
    @Test
    void shouldAuditChangedRoleWithDeterministicSortedValues() {
        service.update(request(grants("ADMIN", EXPOSED), grants("MANAGER", without(EXPOSED, "MANAGE_USER")),
                grants("STAFF", "MANAGE_ROOM", "VIEW_BOOKING", "CHECK_IN", "CHECK_OUT", "CHANGE_ROOM", "EXTEND_STAY", "MANAGE_PAYMENT")));

        ArgumentCaptor<AuditLog> captor = ArgumentCaptor.forClass(AuditLog.class);
        verify(audits).save(captor.capture());
        AuditLog log = captor.getValue();
        assertEquals("ROLE_PERMISSION_CHANGE", field(log, "action"));
        assertEquals("ROLE", field(log, "entityType"));
        assertEquals(field(staff, "id"), field(log, "entityId"));
        assertEquals(actorId, field(log, "userId"));
        assertEquals("CHANGE_ROOM,CHECK_IN,CHECK_OUT,EXTEND_STAY,MANAGE_PAYMENT,VIEW_BOOKING", field(log, "oldValue"));
        assertEquals("CHANGE_ROOM,CHECK_IN,CHECK_OUT,EXTEND_STAY,MANAGE_PAYMENT,MANAGE_ROOM,VIEW_BOOKING", field(log, "newValue"));
    }

    /** Confirms the built-in role rows are locked before roles are loaded or changed. */
    @Test
    void shouldLockRolesBeforeLoadingAndChanging() {
        service.update(request(grants("ADMIN", EXPOSED), grants("MANAGER", without(EXPOSED, "MANAGE_USER")),
                grants("STAFF", "MANAGE_ROOM", "VIEW_BOOKING", "CHECK_IN", "CHECK_OUT", "CHANGE_ROOM", "EXTEND_STAY", "MANAGE_PAYMENT")));

        InOrder order = inOrder(roles);
        order.verify(roles).lockBuiltInRoleIds();
        order.verify(roles).findByCodeIn(any());
        order.verify(roles).saveAndFlush(staff);
    }

    /** Confirms a database locking failure is reported as a friendly conflict, not a raw exception. */
    @Test
    void shouldReportLockFailureFriendly() {
        when(roles.lockBuiltInRoleIds()).thenThrow(new CannotAcquireLockException("lock timeout"));

        ResponseStatusException exception = assertThrows(ResponseStatusException.class, () -> service.update(validRequest()));

        assertEquals(409, exception.getStatusCode().value());
        assertFalse(exception.getReason().toLowerCase().contains("lock timeout"));
    }

    /** Confirms a missing built-in role is reported without any change. */
    @Test
    void shouldRejectWhenBuiltInRoleMissing() {
        when(roles.findByCodeIn(any())).thenReturn(List.of(admin, manager));

        assertThrows(ResponseStatusException.class, () -> service.update(validRequest()));
        assertThrows(ResponseStatusException.class, service::loadMatrix);
        verify(roles, never()).saveAndFlush(any());
    }

    private RolePermissionUpdateRequest validRequest() {
        return request(grants("ADMIN", EXPOSED), grants("MANAGER", without(EXPOSED, "MANAGE_USER")),
                grants("STAFF", "VIEW_BOOKING", "CHECK_IN", "CHECK_OUT", "CHANGE_ROOM", "EXTEND_STAY", "MANAGE_PAYMENT"));
    }

    @SafeVarargs
    private RolePermissionUpdateRequest request(Map.Entry<String, List<String>>... entries) {
        RolePermissionUpdateRequest request = new RolePermissionUpdateRequest();
        request.setSubmittedRoles(new ArrayList<>(List.of("ADMIN", "MANAGER", "STAFF")));
        Map<String, List<String>> grants = new LinkedHashMap<>();
        for (Map.Entry<String, List<String>> entry : entries) {
            grants.put(entry.getKey(), new ArrayList<>(entry.getValue()));
        }
        request.setGrants(grants);
        return request;
    }

    private Map.Entry<String, List<String>> grants(String role, String... codes) {
        return Map.entry(role, Arrays.asList(codes));
    }

    private Map.Entry<String, List<String>> grants(String role, List<String> codes) {
        return Map.entry(role, codes);
    }

    private List<String> without(List<String> all, String... excluded) {
        List<String> remaining = new ArrayList<>(all);
        remaining.removeAll(Arrays.asList(excluded));
        return remaining;
    }

    private List<String> codes(Role role) {
        return role.getPermissions().stream().map(Permission::getCode).sorted().toList();
    }

    private Permission permission(String code) throws Exception {
        var constructor = Permission.class.getDeclaredConstructor();
        constructor.setAccessible(true);
        Permission permission = constructor.newInstance();
        set(permission, "code", code);
        set(permission, "id", UUID.randomUUID());
        return permission;
    }

    private Role role(String code, String... permissionCodes) throws Exception {
        var constructor = Role.class.getDeclaredConstructor();
        constructor.setAccessible(true);
        Role role = constructor.newInstance();
        set(role, "code", code);
        set(role, "id", UUID.randomUUID());
        for (String permissionCode : permissionCodes) {
            role.getPermissions().add(catalogue.get(permissionCode));
        }
        return role;
    }

    private void set(Object target, String field, Object value) throws Exception {
        Field declared = target.getClass().getDeclaredField(field);
        declared.setAccessible(true);
        declared.set(target, value);
    }

    private Object field(Object target, String field) {
        try {
            Field declared = target.getClass().getDeclaredField(field);
            declared.setAccessible(true);
            return declared.get(target);
        } catch (ReflectiveOperationException exception) {
            throw new IllegalStateException(exception);
        }
    }
}
