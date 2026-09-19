package com.example.hotel.service.common;

import com.example.hotel.dto.common.request.RolePermissionUpdateRequest;
import com.example.hotel.dto.common.response.RolePermissionMatrixResponse;
import com.example.hotel.entity.common.AuditLog;
import com.example.hotel.entity.common.Permission;
import com.example.hotel.entity.common.Role;
import com.example.hotel.repository.common.AuditLogRepository;
import com.example.hotel.repository.common.PermissionRepository;
import com.example.hotel.repository.common.RoleRepository;
import com.example.hotel.security.CurrentUser;
import com.example.hotel.security.SessionUserPrincipal;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.stream.Collectors;
import org.springframework.dao.DataAccessException;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

/**
 * Manages the permission matrix of the three fixed built-in roles. Roles and permissions are never
 * created, renamed or deleted. MANAGE_USER is a protected invariant: always granted to ADMIN and
 * never granted to any other role, regardless of what the browser submits.
 */
@Service
public class RolePermissionService {

    /** Built-in role codes in display order. */
    public static final List<String> BUILT_IN_ROLES = List.of("ADMIN", "MANAGER", "STAFF");

    static final String PROTECTED_PERMISSION = "MANAGE_USER";
    private static final String ADMIN_ROLE = "ADMIN";
    private static final String AUDIT_ACTION = "ROLE_PERMISSION_CHANGE";
    private static final String AUDIT_ENTITY_TYPE = "ROLE";

    private static final Map<String, List<Entry>> GROUPS = new LinkedHashMap<>();

    static {
        GROUPS.put("Dashboard", List.of(new Entry("VIEW_REPORT", "View Reports")));
        GROUPS.put("Reservations", List.of(
                new Entry("VIEW_BOOKING", "View Booking"),
                new Entry("MANAGE_BOOKING", "Manage Booking"),
                new Entry("CHECK_IN", "Check In"),
                new Entry("CHECK_OUT", "Check Out"),
                new Entry("CHANGE_ROOM", "Change Room")));
        GROUPS.put("Guests", List.of(new Entry("MANAGE_GUEST", "Manage Guest")));
        GROUPS.put("Rooms", List.of(new Entry("MANAGE_ROOM", "Manage Room")));
        GROUPS.put("Finance", List.of(
                new Entry("MANAGE_PAYMENT", "Manage Payment"),
                new Entry("MANAGE_EXPENSE", "Manage Expense"),
                new Entry("MANAGE_ADDITIONAL_REVENUE", "Additional Revenue")));
        GROUPS.put("Administration", List.of(
                new Entry("MANAGE_STAFF", "Manage Staff"),
                new Entry("MANAGE_ATTENDANCE", "Attendance"),
                new Entry(PROTECTED_PERMISSION, "Manage Users")));
    }

    private static final Set<String> EXPOSED_CODES = GROUPS.values().stream()
            .flatMap(List::stream)
            .map(Entry::code)
            .collect(Collectors.toUnmodifiableSet());

    private final RoleRepository roleRepository;
    private final PermissionRepository permissionRepository;
    private final AuditLogRepository auditLogRepository;

    /**
     * Creates the Role permission service.
     *
     * @param roleRepository repository for roles
     * @param permissionRepository repository for the permission catalogue
     * @param auditLogRepository repository for audit entries
     */
    public RolePermissionService(
            RoleRepository roleRepository,
            PermissionRepository permissionRepository,
            AuditLogRepository auditLogRepository) {
        this.roleRepository = roleRepository;
        this.permissionRepository = permissionRepository;
        this.auditLogRepository = auditLogRepository;
    }

    /**
     * Loads the current matrix from the database for the three built-in roles and the exposed
     * permissions. Permissions that are not exposed (such as the dormant DELETE_RESERVATION) never
     * appear.
     *
     * @return the current matrix
     * @throws ResponseStatusException if a built-in role is missing
     */
    @Transactional(readOnly = true)
    public RolePermissionMatrixResponse loadMatrix() {
        Map<String, Role> roles = builtInRoles();
        List<RolePermissionMatrixResponse.Group> groups = new ArrayList<>();
        GROUPS.forEach((label, entries) -> {
            List<RolePermissionMatrixResponse.Item> items = new ArrayList<>();
            for (Entry entry : entries) {
                boolean locked = PROTECTED_PERMISSION.equals(entry.code());
                Map<String, Boolean> granted = new LinkedHashMap<>();
                for (String roleCode : BUILT_IN_ROLES) {
                    granted.put(roleCode, locked
                            ? ADMIN_ROLE.equals(roleCode)
                            : codesOf(roles.get(roleCode)).contains(entry.code()));
                }
                items.add(new RolePermissionMatrixResponse.Item(entry.code(), entry.label(), locked, granted));
            }
            groups.add(new RolePermissionMatrixResponse.Group(label, items));
        });
        return new RolePermissionMatrixResponse(BUILT_IN_ROLES, groups);
    }

    /**
     * Saves the submitted matrix atomically. The whole submission is validated before any change;
     * the three built-in role rows are locked so concurrent saves are serialized; permissions that
     * are not exposed are preserved exactly as they are; only roles whose permission set actually
     * changes are updated and audited.
     *
     * @param request the untrusted submitted matrix
     * @throws ResponseStatusException if the submission is malformed or violates an invariant
     */
    @Transactional
    public void update(RolePermissionUpdateRequest request) {
        Map<String, Set<String>> desired = validate(request);
        CurrentUser actor = currentUser();
        try {
            roleRepository.lockBuiltInRoleIds();
            Map<String, Role> roles = builtInRoles();
            Map<String, Permission> catalogue = exposedPermissions();

            for (String roleCode : BUILT_IN_ROLES) {
                Role role = roles.get(roleCode);
                Set<String> before = codesOf(role);
                Set<String> after = new TreeSet<>();
                before.stream().filter(code -> !EXPOSED_CODES.contains(code)).forEach(after::add);
                after.addAll(desired.get(roleCode));
                if (before.equals(after)) {
                    continue;
                }
                Set<Permission> newPermissions = new HashSet<>();
                for (Permission permission : role.getPermissions()) {
                    if (after.contains(permission.getCode())) {
                        newPermissions.add(permission);
                    }
                }
                for (String code : after) {
                    if (EXPOSED_CODES.contains(code)) {
                        newPermissions.add(catalogue.get(code));
                    }
                }
                role.replacePermissions(newPermissions);
                role.audit(actor.id());
                roleRepository.saveAndFlush(role);
                auditLogRepository.save(new AuditLog(
                        actor.id(), AUDIT_ACTION, AUDIT_ENTITY_TYPE, role.getId(),
                        String.join(",", before), String.join(",", after)));
            }
        } catch (DataAccessException exception) {
            throw new ResponseStatusException(
                    HttpStatus.CONFLICT, "Role permissions could not be saved. Please reload the page and try again.");
        }
    }

    /**
     * Validates the untrusted submission and derives the desired exposed permission set per role.
     *
     * @param request the submitted matrix
     * @return desired exposed permission codes per built-in role, with the protected invariant applied
     */
    private Map<String, Set<String>> validate(RolePermissionUpdateRequest request) {
        if (request == null
                || request.getSubmittedRoles().size() != BUILT_IN_ROLES.size()
                || !new HashSet<>(request.getSubmittedRoles()).equals(new HashSet<>(BUILT_IN_ROLES))) {
            throw badRequest("The submitted permission matrix is invalid. Please reload the page and try again.");
        }
        for (String roleCode : request.getGrants().keySet()) {
            if (!BUILT_IN_ROLES.contains(roleCode)) {
                throw badRequest("The submitted permission matrix is invalid. Please reload the page and try again.");
            }
        }
        Map<String, Set<String>> desired = new LinkedHashMap<>();
        for (String roleCode : BUILT_IN_ROLES) {
            List<String> submitted = request.getGrants().getOrDefault(roleCode, List.of());
            Set<String> codes = new LinkedHashSet<>();
            for (String code : submitted) {
                if (code == null || !EXPOSED_CODES.contains(code)) {
                    throw badRequest("The submitted permission matrix contains an unknown permission.");
                }
                if (PROTECTED_PERMISSION.equals(code) && !ADMIN_ROLE.equals(roleCode)) {
                    throw badRequest("Manage Users is reserved for the ADMIN role and cannot be granted to other roles.");
                }
                codes.add(code);
            }
            if (ADMIN_ROLE.equals(roleCode)) {
                codes.add(PROTECTED_PERMISSION);
            }
            desired.put(roleCode, codes);
        }
        return desired;
    }

    /**
     * Loads the three built-in roles by code.
     *
     * @return built-in roles keyed by code
     * @throws ResponseStatusException if any built-in role is missing
     */
    private Map<String, Role> builtInRoles() {
        Map<String, Role> roles = roleRepository.findByCodeIn(BUILT_IN_ROLES).stream()
                .collect(Collectors.toMap(Role::getCode, role -> role));
        if (!roles.keySet().containsAll(BUILT_IN_ROLES)) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "The built-in roles are not configured correctly.");
        }
        return roles;
    }

    /**
     * Loads every exposed permission from the catalogue table.
     *
     * @return exposed permissions keyed by code
     * @throws ResponseStatusException if a required permission is missing from the database
     */
    private Map<String, Permission> exposedPermissions() {
        Map<String, Permission> permissions = permissionRepository.findByCodeIn(EXPOSED_CODES).stream()
                .collect(Collectors.toMap(Permission::getCode, permission -> permission));
        if (!permissions.keySet().containsAll(EXPOSED_CODES)) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "The permission catalogue is incomplete.");
        }
        return permissions;
    }

    /**
     * Returns a role's permission codes in deterministic sorted order.
     *
     * @param role the role
     * @return sorted permission codes
     */
    private Set<String> codesOf(Role role) {
        return role.getPermissions().stream()
                .map(Permission::getCode)
                .collect(Collectors.toCollection(TreeSet::new));
    }

    /**
     * Resolves the authenticated user recorded as the audit actor.
     *
     * @return the authenticated application user
     */
    private CurrentUser currentUser() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        Object principal = authentication == null ? null : authentication.getPrincipal();
        if (principal instanceof CurrentUser currentUser) {
            return currentUser;
        }
        if (principal instanceof SessionUserPrincipal sessionUserPrincipal) {
            return new CurrentUser(sessionUserPrincipal.id(), sessionUserPrincipal.getUsername());
        }
        throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Unauthenticated user");
    }

    private ResponseStatusException badRequest(String message) {
        return new ResponseStatusException(HttpStatus.BAD_REQUEST, message);
    }

    /** One permission with its human-readable label. */
    private record Entry(String code, String label) {}
}
