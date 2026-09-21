package com.example.hotel.service.common;

import com.example.hotel.dto.common.request.UserCreateRequest;
import com.example.hotel.dto.common.request.UserPasswordResetRequest;
import com.example.hotel.dto.common.request.UserSearchCriteria;
import com.example.hotel.dto.common.request.UserUpdateRequest;
import com.example.hotel.dto.common.response.StaffResponse;
import com.example.hotel.dto.common.response.UserResponse;
import com.example.hotel.entity.common.AppUser;
import com.example.hotel.entity.common.AuditLog;
import com.example.hotel.entity.common.Role;
import com.example.hotel.entity.common.Staff;
import com.example.hotel.mapper.common.StaffMapper;
import com.example.hotel.repository.common.AppUserRepository;
import com.example.hotel.repository.common.AuditLogRepository;
import com.example.hotel.repository.common.RoleRepository;
import com.example.hotel.repository.common.StaffRepository;
import com.example.hotel.security.CurrentUser;
import com.example.hotel.security.SessionUserPrincipal;
import jakarta.persistence.criteria.Predicate;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

/**
 * Manages PMS user accounts: list, create, edit (Staff link and single managed role), password
 * reset, and activation/deactivation. Accounts are never deleted. Every security-sensitive
 * mutation is written to the audit log without any credential material.
 */
@Service
public class UserService {

    /** Role codes that User Management V1 may assign, in display order. */
    public static final List<String> ASSIGNABLE_ROLES = List.of("ADMIN", "MANAGER", "STAFF");

    private static final String ADMIN_ROLE = "ADMIN";
    private static final String ENTITY_TYPE = "APP_USER";
    private static final Pattern USERNAME_PATTERN = Pattern.compile("^[a-z0-9._-]{3,50}$");
    private static final int PASSWORD_MIN_LENGTH = 8;
    private static final int PASSWORD_MAX_LENGTH = 72;

    private final AppUserRepository appUserRepository;
    private final RoleRepository roleRepository;
    private final StaffRepository staffRepository;
    private final AuditLogRepository auditLogRepository;
    private final PasswordEncoder passwordEncoder;
    private final StaffMapper staffMapper;

    /**
     * Creates the User service with its persistence and security collaborators.
     *
     * @param appUserRepository repository for user accounts
     * @param roleRepository repository for roles
     * @param staffRepository repository for Staff members
     * @param auditLogRepository repository for security audit entries
     * @param passwordEncoder encoder used to hash passwords
     * @param staffMapper mapper used to produce Staff selector options
     */
    public UserService(
            AppUserRepository appUserRepository,
            RoleRepository roleRepository,
            StaffRepository staffRepository,
            AuditLogRepository auditLogRepository,
            PasswordEncoder passwordEncoder,
            StaffMapper staffMapper) {
        this.appUserRepository = appUserRepository;
        this.roleRepository = roleRepository;
        this.staffRepository = staffRepository;
        this.auditLogRepository = auditLogRepository;
        this.passwordEncoder = passwordEncoder;
        this.staffMapper = staffMapper;
    }

    /**
     * Searches user accounts by username fragment and status, in username order.
     *
     * @param criteria normalized optional filters
     * @return matching user accounts
     */
    @Transactional(readOnly = true)
    public List<UserResponse> search(UserSearchCriteria criteria) {
        List<AppUser> users = appUserRepository.findAll(specificationFor(criteria), Sort.by("username"));
        Map<UUID, Staff> staffByUserId = new HashMap<>();
        if (!users.isEmpty()) {
            for (Staff staff : staffRepository.findByAppUserIdIn(users.stream().map(AppUser::getId).toList())) {
                staffByUserId.put(staff.getAppUserId(), staff);
            }
        }
        return users.stream().map(user -> toResponse(user, staffByUserId.get(user.getId()))).toList();
    }

    /**
     * Finds one user account.
     *
     * @param id user account identifier
     * @return the client-safe user account
     * @throws ResponseStatusException if the account does not exist
     */
    @Transactional(readOnly = true)
    public UserResponse findById(UUID id) {
        AppUser user = findUser(id);
        return toResponse(user, staffRepository.findByAppUserId(id).orElse(null));
    }

    /**
     * Lists the Staff members that may be linked to an account: every Staff member without an
     * account, plus the Staff member already linked to the given account.
     *
     * @param userId the account being edited, or {@code null} when creating
     * @return selectable Staff members in Staff Code order
     */
    @Transactional(readOnly = true)
    public List<StaffResponse> linkableStaff(UUID userId) {
        List<Staff> staff = new ArrayList<>(staffRepository.findByAppUserIdIsNullOrderByStaffCodeAsc());
        if (userId != null) {
            staffRepository.findByAppUserId(userId).ifPresent(staff::add);
        }
        return staff.stream()
                .sorted((left, right) -> left.getStaffCode().compareTo(right.getStaffCode()))
                .map(staffMapper::toResponse)
                .toList();
    }

    /**
     * Creates an active user account, optionally linked to a Staff member.
     *
     * @param request client-supplied account data
     * @return the created account
     * @throws ResponseStatusException if any field is invalid or the Staff link is unsafe
     */
    @Transactional
    public UserResponse create(UserCreateRequest request) {
        List<String> errors = new ArrayList<>();
        String username = normalizeUsername(request.username());
        if (username == null || !USERNAME_PATTERN.matcher(username).matches()) {
            errors.add("Username must be 3 to 50 characters using only letters, digits, '.', '_' and '-'.");
        } else if (appUserRepository.existsByUsernameIgnoreCase(username)) {
            errors.add("Username is already taken.");
        }
        validatePassword(request.password(), request.confirmPassword(), errors);
        Role role = resolveRole(request.role(), errors);
        Staff staff = null;
        if (request.staffId() != null) {
            staff = staffRepository.findById(request.staffId()).orElse(null);
            if (staff == null) {
                errors.add("Staff member not found.");
            } else if (staff.getAppUserId() != null) {
                errors.add("This Staff member already has a user account.");
            } else if (!staff.isActive()) {
                errors.add("An active account cannot be linked to an inactive Staff member.");
            }
        }
        if (!errors.isEmpty()) {
            throw badRequest(String.join(" ", errors));
        }

        UUID actorId = currentUser().id();
        AppUser user = new AppUser(UUID.randomUUID(), username, passwordEncoder.encode(request.password()));
        user.replaceRoles(role);
        user.audit(actorId);
        try {
            appUserRepository.saveAndFlush(user);
            if (staff != null) {
                staff.linkAppUser(user.getId());
                staff.audit(actorId);
                staffRepository.saveAndFlush(staff);
            }
        } catch (DataIntegrityViolationException exception) {
            throw conflict("Username is already taken or the Staff member already has a user account.");
        }
        return toResponse(user, staff);
    }

    /**
     * Updates the Staff link and the single managed role of an account. The username is immutable.
     *
     * @param id user account identifier
     * @param request replacement Staff link and role
     * @return the updated account
     * @throws ResponseStatusException if the change is invalid or violates a safety rule
     */
    @Transactional
    public UserResponse update(UUID id, UserUpdateRequest request) {
        AppUser user = findUser(id);
        List<String> errors = new ArrayList<>();
        Role newRole = resolveRole(request.role(), errors);
        if (!errors.isEmpty()) {
            throw badRequest(String.join(" ", errors));
        }
        UUID actorId = currentUser().id();

        boolean roleChanging = !(user.getRoles().size() == 1 && user.getRoles().contains(newRole));
        if (roleChanging) {
            if (user.getId().equals(actorId)) {
                throw conflict("You cannot change your own role.");
            }
            if (isActiveAdmin(user) && !ADMIN_ROLE.equals(newRole.getCode())) {
                guardLastActiveAdmin(user);
            }
        }

        Optional<Staff> currentLink = staffRepository.findByAppUserId(id);
        UUID currentStaffId = currentLink.map(Staff::getId).orElse(null);
        Staff linkedStaff = currentLink.orElse(null);
        if (!Objects.equals(request.staffId(), currentStaffId)) {
            Staff target = null;
            if (request.staffId() != null) {
                target = staffRepository
                        .findById(request.staffId())
                        .orElseThrow(() -> badRequest("Staff member not found."));
                if (target.getAppUserId() != null && !target.getAppUserId().equals(id)) {
                    throw conflict("This Staff member already has a user account.");
                }
                if (user.isActive() && !target.isActive()) {
                    throw conflict("An active account cannot be linked to an inactive Staff member.");
                }
            }
            if (linkedStaff != null) {
                linkedStaff.unlinkAppUser();
                linkedStaff.audit(actorId);
                staffRepository.saveAndFlush(linkedStaff);
            }
            if (target != null) {
                target.linkAppUser(id);
                target.audit(actorId);
                staffRepository.saveAndFlush(target);
            }
            linkedStaff = target;
        }

        if (roleChanging) {
            String oldRoles = roleCodes(user);
            user.replaceRoles(newRole);
            user.audit(actorId);
            appUserRepository.saveAndFlush(user);
            audit(actorId, "USER_ROLE_CHANGE", id, oldRoles, newRole.getCode());
        }
        return toResponse(user, linkedStaff);
    }

    /**
     * Replaces an account's password with an administrator-supplied one.
     *
     * @param id user account identifier
     * @param request replacement password and confirmation
     * @throws ResponseStatusException if the password is invalid or the account does not exist
     */
    @Transactional
    public void resetPassword(UUID id, UserPasswordResetRequest request) {
        AppUser user = findUser(id);
        List<String> errors = new ArrayList<>();
        validatePassword(request.newPassword(), request.confirmPassword(), errors);
        if (!errors.isEmpty()) {
            throw badRequest(String.join(" ", errors));
        }
        UUID actorId = currentUser().id();
        user.resetPassword(passwordEncoder.encode(request.newPassword()));
        user.audit(actorId);
        appUserRepository.save(user);
        audit(actorId, "USER_PASSWORD_RESET", id, null, null);
    }

    /**
     * Activates an inactive account, unless it is linked to an inactive Staff member.
     *
     * @param id user account identifier
     * @return the activated account
     * @throws ResponseStatusException if the account is already active or linked to inactive Staff
     */
    @Transactional
    public UserResponse activate(UUID id) {
        AppUser user = findUser(id);
        Staff staff = staffRepository.findByAppUserId(id).orElse(null);
        if (user.isActive()) {
            throw conflict("User account is already active.");
        }
        if (staff != null && !staff.isActive()) {
            throw conflict("An account linked to an inactive Staff member cannot be activated.");
        }
        UUID actorId = currentUser().id();
        user.activate();
        user.audit(actorId);
        appUserRepository.saveAndFlush(user);
        audit(actorId, "USER_ACTIVATE", id, "INACTIVE", "ACTIVE");
        return toResponse(user, staff);
    }

    /**
     * Deactivates an active account. The account keeps its roles and Staff link and is never
     * deleted. An administrator cannot deactivate their own account, and the last active ADMIN
     * cannot be deactivated.
     *
     * @param id user account identifier
     * @return the deactivated account
     * @throws ResponseStatusException if the account is already inactive or a safety rule blocks it
     */
    @Transactional
    public UserResponse deactivate(UUID id) {
        AppUser user = findUser(id);
        UUID actorId = currentUser().id();
        if (user.getId().equals(actorId)) {
            throw conflict("You cannot deactivate your own account.");
        }
        if (!user.isActive()) {
            throw conflict("User account is already inactive.");
        }
        if (isActiveAdmin(user)) {
            guardLastActiveAdmin(user);
        }
        user.deactivate();
        user.audit(actorId);
        appUserRepository.saveAndFlush(user);
        audit(actorId, "USER_DEACTIVATE", id, "ACTIVE", "INACTIVE");
        return toResponse(user, staffRepository.findByAppUserId(id).orElse(null));
    }

    /**
     * Deactivates the account linked to a Staff member that is being deactivated, inside the
     * caller's transaction. Does nothing when no account is linked or it is already inactive.
     * Reactivating Staff never reactivates the account.
     *
     * @param staff the Staff member being deactivated
     * @param actorId the authenticated user performing the Staff deactivation
     * @throws ResponseStatusException if the linked account is the last active ADMIN
     */
    @Transactional
    public void deactivateLinkedAccount(Staff staff, UUID actorId) {
        if (staff.getAppUserId() == null) {
            return;
        }
        AppUser user = appUserRepository.findById(staff.getAppUserId()).orElse(null);
        if (user == null || !user.isActive()) {
            return;
        }
        if (isActiveAdmin(user)) {
            guardLastActiveAdmin(user);
        }
        user.deactivate();
        user.audit(actorId);
        appUserRepository.saveAndFlush(user);
        audit(actorId, "USER_DEACTIVATE", user.getId(), "ACTIVE", "INACTIVE (Staff deactivated)");
    }

    /**
     * Blocks an operation that would leave the system without an active ADMIN. Locks every active
     * ADMIN row first, so concurrent mutations are serialized rather than raced.
     *
     * @param user the ADMIN account about to lose its active ADMIN status
     * @throws ResponseStatusException if that account is the last active ADMIN
     */
    private void guardLastActiveAdmin(AppUser user) {
        List<UUID> activeAdminIds = appUserRepository.lockActiveAdminIds();
        if (activeAdminIds.size() <= 1 && activeAdminIds.contains(user.getId())) {
            throw conflict("At least one active ADMIN account must remain.");
        }
    }

    /**
     * Determines whether an account is an active ADMIN.
     *
     * @param user the account to inspect
     * @return {@code true} when active and holding the ADMIN role
     */
    private boolean isActiveAdmin(AppUser user) {
        return user.isActive() && user.getRoles().stream().anyMatch(role -> ADMIN_ROLE.equals(role.getCode()));
    }

    /**
     * Builds the case-insensitive database predicate for the list filters.
     *
     * @param criteria normalized optional filters
     * @return the database specification
     */
    private Specification<AppUser> specificationFor(UserSearchCriteria criteria) {
        return (root, query, criteriaBuilder) -> {
            List<Predicate> predicates = new ArrayList<>();
            if (criteria.getQuery() != null) {
                String pattern = "%" + criteria.getQuery().toLowerCase(Locale.ROOT) + "%";
                predicates.add(criteriaBuilder.like(criteriaBuilder.lower(root.get("username")), pattern));
            }
            if (criteria.hasStatusFilter()) {
                predicates.add(criteriaBuilder.equal(root.get("active"), criteria.isActiveFilter()));
            }
            return criteriaBuilder.and(predicates.toArray(new Predicate[0]));
        };
    }

    /**
     * Validates a password and its confirmation, appending friendly messages for each violation.
     *
     * @param password submitted password
     * @param confirmation submitted confirmation
     * @param errors accumulator for validation messages
     */
    private void validatePassword(String password, String confirmation, List<String> errors) {
        if (password == null
                || password.length() < PASSWORD_MIN_LENGTH
                || password.length() > PASSWORD_MAX_LENGTH
                || password.getBytes(StandardCharsets.UTF_8).length > PASSWORD_MAX_LENGTH) {
            errors.add("Password must be between " + PASSWORD_MIN_LENGTH + " and " + PASSWORD_MAX_LENGTH
                    + " characters.");
        } else if (!password.equals(confirmation)) {
            errors.add("Password and confirmation do not match.");
        }
    }

    /**
     * Resolves an assignable role, appending a message when it is missing or not assignable.
     *
     * @param roleCode submitted role code
     * @param errors accumulator for validation messages
     * @return the role, or {@code null} when invalid
     */
    private Role resolveRole(String roleCode, List<String> errors) {
        if (roleCode == null || roleCode.isBlank()) {
            errors.add("Role is required.");
            return null;
        }
        String normalized = roleCode.trim().toUpperCase(Locale.ROOT);
        Role role = ASSIGNABLE_ROLES.contains(normalized) ? roleRepository.findByCode(normalized).orElse(null) : null;
        if (role == null) {
            errors.add("Role is invalid.");
        }
        return role;
    }

    /**
     * Trims and lowercases a submitted username.
     *
     * @param username submitted username
     * @return the normalized username, or {@code null} when blank
     */
    private String normalizeUsername(String username) {
        if (username == null || username.isBlank()) {
            return null;
        }
        return username.trim().toLowerCase(Locale.ROOT);
    }

    /**
     * Maps an account and its optional linked Staff member to a client-safe response.
     *
     * @param user the account
     * @param staff the linked Staff member, or {@code null}
     * @return the response without credential data
     */
    private UserResponse toResponse(AppUser user, Staff staff) {
        return new UserResponse(
                user.getId(),
                user.getUsername(),
                staff == null ? null : staff.getId(),
                staff == null ? null : staff.getStaffCode(),
                staff == null ? null : staff.getFirstName() + " " + staff.getLastName(),
                staff == null || staff.isActive(),
                roleCodes(user),
                user.isActive());
    }

    /**
     * Joins an account's role codes for display and audit values.
     *
     * @param user the account
     * @return sorted role codes, or an empty string when none is assigned
     */
    private String roleCodes(AppUser user) {
        Set<String> codes = user.getRoles().stream().map(Role::getCode).collect(Collectors.toSet());
        return codes.stream().sorted().collect(Collectors.joining(", "));
    }

    /**
     * Records a security-sensitive account mutation without any credential material.
     *
     * @param actorId the authenticated user who performed the mutation
     * @param action the audited action code
     * @param targetId the affected account identifier
     * @param oldValue safe previous value, or {@code null}
     * @param newValue safe new value, or {@code null}
     */
    private void audit(UUID actorId, String action, UUID targetId, String oldValue, String newValue) {
        auditLogRepository.save(new AuditLog(actorId, action, ENTITY_TYPE, targetId, oldValue, newValue));
    }

    /**
     * Loads an account or produces the standard not-found response.
     *
     * @param id account identifier
     * @return the persisted account
     */
    private AppUser findUser(UUID id) {
        return appUserRepository.findById(id).orElseThrow(() -> new ResponseStatusException(
                HttpStatus.NOT_FOUND, "User account not found"));
    }

    /**
     * Resolves the current JWT or session principal to the audit identity.
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

    /**
     * Creates a standard bad-request response.
     *
     * @param message browser-safe validation message
     * @return bad-request response exception
     */
    private ResponseStatusException badRequest(String message) {
        return new ResponseStatusException(HttpStatus.BAD_REQUEST, message);
    }

    /**
     * Creates a standard conflict response.
     *
     * @param message state-conflict message
     * @return conflict response exception
     */
    private ResponseStatusException conflict(String message) {
        return new ResponseStatusException(HttpStatus.CONFLICT, message);
    }
}
