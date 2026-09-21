package com.example.hotel.service.common;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.example.hotel.dto.common.request.UserCreateRequest;
import com.example.hotel.dto.common.request.UserPasswordResetRequest;
import com.example.hotel.dto.common.request.UserUpdateRequest;
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
import java.lang.reflect.Field;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.web.server.ResponseStatusException;

/** Verifies User Management rules: usernames, passwords, Staff link, lifecycle, safety, and audit. */
class UserServiceTest {

    private final AppUserRepository users = mock(AppUserRepository.class);
    private final RoleRepository roles = mock(RoleRepository.class);
    private final StaffRepository staffRepository = mock(StaffRepository.class);
    private final AuditLogRepository audits = mock(AuditLogRepository.class);
    private final PasswordEncoder encoder = new BCryptPasswordEncoder();
    private final UserService service =
            new UserService(users, roles, staffRepository, audits, encoder, new StaffMapper());

    private final UUID actorId = UUID.randomUUID();
    private Role adminRole;
    private Role managerRole;
    private Role staffRole;

    /** Establishes the acting administrator and the three assignable roles. */
    @BeforeEach
    void setUp() throws Exception {
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(new CurrentUser(actorId, "actor"), null));
        adminRole = role("ADMIN");
        managerRole = role("MANAGER");
        staffRole = role("STAFF");
        when(roles.findByCode("ADMIN")).thenReturn(Optional.of(adminRole));
        when(roles.findByCode("MANAGER")).thenReturn(Optional.of(managerRole));
        when(roles.findByCode("STAFF")).thenReturn(Optional.of(staffRole));
        when(users.saveAndFlush(any(AppUser.class))).thenAnswer(invocation -> invocation.getArgument(0));
        when(staffRepository.findByAppUserId(any())).thenReturn(Optional.empty());
    }

    /** Clears the authenticated user. */
    @AfterEach
    void clear() {
        SecurityContextHolder.clearContext();
    }

    /** Confirms creating a standalone account normalizes the username and stores only a BCrypt hash. */
    @Test
    void shouldCreateStandaloneUserWithLowercaseUsernameAndBcryptHash() {
        UserResponse created = service.create(new UserCreateRequest(null, "  An.Le ", "password1", "password1", "STAFF"));

        ArgumentCaptor<AppUser> captor = ArgumentCaptor.forClass(AppUser.class);
        verify(users).saveAndFlush(captor.capture());
        AppUser saved = captor.getValue();
        assertEquals("an.le", saved.getUsername());
        assertEquals("an.le", created.username());
        assertTrue(saved.getPasswordHash().startsWith("$2"));
        assertFalse(saved.getPasswordHash().contains("password1"));
        assertTrue(encoder.matches("password1", saved.getPasswordHash()));
        assertTrue(saved.isActive());
        assertEquals(1, saved.getRoles().size());
        assertNull(created.staffId());
    }

    /** Confirms a created account can be linked to an unlinked active Staff member. */
    @Test
    void shouldCreateStaffLinkedUser() {
        Staff staff = staff("STF-000001", true);
        when(staffRepository.findById(staff.getId())).thenReturn(Optional.of(staff));

        UserResponse created =
                service.create(new UserCreateRequest(staff.getId(), "an.le", "password1", "password1", "MANAGER"));

        assertEquals(staff.getId(), created.staffId());
        assertEquals(created.id(), staff.getAppUserId());
    }

    /** Confirms invalid username characters and lengths are rejected with a friendly message. */
    @Test
    void shouldRejectInvalidUsernames() {
        for (String invalid : new String[] {"ab", "a".repeat(51), "bad name", "bad@name", "", null}) {
            ResponseStatusException exception = assertThrows(ResponseStatusException.class,
                    () -> service.create(new UserCreateRequest(null, invalid, "password1", "password1", "STAFF")));
            assertTrue(exception.getReason().contains("Username must be 3 to 50"));
        }
        verify(users, never()).saveAndFlush(any());
    }

    /** Confirms the boundary lengths 3 and 50 are accepted. */
    @Test
    void shouldAcceptUsernameBoundaryLengths() {
        service.create(new UserCreateRequest(null, "abc", "password1", "password1", "STAFF"));
        service.create(new UserCreateRequest(null, "a".repeat(50), "password1", "password1", "STAFF"));
        verify(users, org.mockito.Mockito.times(2)).saveAndFlush(any(AppUser.class));
    }

    /** Confirms a case-variant duplicate username is rejected. */
    @Test
    void shouldRejectCaseInsensitiveDuplicateUsername() {
        when(users.existsByUsernameIgnoreCase("an.le")).thenReturn(true);

        ResponseStatusException exception = assertThrows(ResponseStatusException.class,
                () -> service.create(new UserCreateRequest(null, "AN.LE", "password1", "password1", "STAFF")));

        assertTrue(exception.getReason().contains("already taken"));
    }

    /** Confirms password length and confirmation rules. */
    @Test
    void shouldValidatePasswordRules() {
        assertRejected(new UserCreateRequest(null, "an.le", "short", "short", "STAFF"), "between 8 and 72");
        assertRejected(new UserCreateRequest(null, "an.le", "a".repeat(73), "a".repeat(73), "STAFF"), "between 8 and 72");
        assertRejected(new UserCreateRequest(null, "an.le", "password1", "password2", "STAFF"), "do not match");
        service.create(new UserCreateRequest(null, "min.pass", "12345678", "12345678", "STAFF"));
        service.create(new UserCreateRequest(null, "max.pass", "a".repeat(72), "a".repeat(72), "STAFF"));
    }

    /** Confirms a missing or non-assignable role is rejected. */
    @Test
    void shouldRejectMissingOrInvalidRole() {
        assertRejected(new UserCreateRequest(null, "an.le", "password1", "password1", null), "Role is required");
        assertRejected(new UserCreateRequest(null, "an.le", "password1", "password1", "SUPERUSER"), "Role is invalid");
    }

    /** Confirms a Staff member already linked, or inactive, or missing cannot be linked on create. */
    @Test
    void shouldRejectUnsafeStaffLinkOnCreate() {
        Staff linked = staff("STF-000001", true);
        linked.linkAppUser(UUID.randomUUID());
        Staff inactive = staff("STF-000002", false);
        when(staffRepository.findById(linked.getId())).thenReturn(Optional.of(linked));
        when(staffRepository.findById(inactive.getId())).thenReturn(Optional.of(inactive));

        assertRejected(new UserCreateRequest(linked.getId(), "an.le", "password1", "password1", "STAFF"), "already has a user account");
        assertRejected(new UserCreateRequest(inactive.getId(), "an.le", "password1", "password1", "STAFF"), "inactive Staff");
        assertRejected(new UserCreateRequest(UUID.randomUUID(), "an.le", "password1", "password1", "STAFF"), "Staff member not found");
    }

    /** Confirms editing replaces the role, audits USER_ROLE_CHANGE with safe old/new codes. */
    @Test
    void shouldChangeRoleAndAudit() {
        AppUser target = user("an.le", true, staffRole);
        when(users.findById(target.getId())).thenReturn(Optional.of(target));

        service.update(target.getId(), new UserUpdateRequest(null, "MANAGER"));

        assertEquals(1, target.getRoles().size());
        assertTrue(target.getRoles().contains(managerRole));
        AuditLog log = savedAudit();
        assertAudit(log, "USER_ROLE_CHANGE", target.getId(), "STAFF", "MANAGER");
    }

    /** Confirms an administrator cannot change their own role. */
    @Test
    void shouldRejectSelfRoleChange() {
        AppUser self = user("admin", true, adminRole);
        setId(self, actorId);
        when(users.findById(actorId)).thenReturn(Optional.of(self));

        assertThrows(ResponseStatusException.class, () -> service.update(actorId, new UserUpdateRequest(null, "STAFF")));
        assertTrue(self.getRoles().contains(adminRole));
    }

    /** Confirms the last active ADMIN cannot be demoted, but can when another active ADMIN exists. */
    @Test
    void shouldProtectLastActiveAdminFromDemotion() {
        AppUser lastAdmin = user("admin2", true, adminRole);
        when(users.findById(lastAdmin.getId())).thenReturn(Optional.of(lastAdmin));
        when(users.lockActiveAdminIds()).thenReturn(List.of(lastAdmin.getId()));

        ResponseStatusException exception = assertThrows(ResponseStatusException.class,
                () -> service.update(lastAdmin.getId(), new UserUpdateRequest(null, "MANAGER")));
        assertTrue(exception.getReason().contains("active ADMIN"));
        assertTrue(lastAdmin.getRoles().contains(adminRole));

        when(users.lockActiveAdminIds()).thenReturn(List.of(lastAdmin.getId(), UUID.randomUUID()));
        service.update(lastAdmin.getId(), new UserUpdateRequest(null, "MANAGER"));
        assertTrue(lastAdmin.getRoles().contains(managerRole));
    }

    /** Confirms editing relinks Staff one-to-one and rejects a Staff member linked to another account. */
    @Test
    void shouldRelinkStaffAndRejectStaffLinkedElsewhere() {
        AppUser target = user("an.le", true, staffRole);
        Staff current = staff("STF-000001", true);
        current.linkAppUser(target.getId());
        Staff next = staff("STF-000002", true);
        Staff taken = staff("STF-000003", true);
        taken.linkAppUser(UUID.randomUUID());
        when(users.findById(target.getId())).thenReturn(Optional.of(target));
        when(staffRepository.findByAppUserId(target.getId())).thenReturn(Optional.of(current));
        when(staffRepository.findById(next.getId())).thenReturn(Optional.of(next));
        when(staffRepository.findById(taken.getId())).thenReturn(Optional.of(taken));

        assertThrows(ResponseStatusException.class,
                () -> service.update(target.getId(), new UserUpdateRequest(taken.getId(), "STAFF")));

        service.update(target.getId(), new UserUpdateRequest(next.getId(), "STAFF"));
        assertNull(current.getAppUserId());
        assertEquals(target.getId(), next.getAppUserId());
    }

    /** Confirms an active account cannot be linked to an inactive Staff member. */
    @Test
    void shouldRejectLinkingActiveUserToInactiveStaff() {
        AppUser target = user("an.le", true, staffRole);
        Staff inactive = staff("STF-000009", false);
        when(users.findById(target.getId())).thenReturn(Optional.of(target));
        when(staffRepository.findById(inactive.getId())).thenReturn(Optional.of(inactive));

        assertThrows(ResponseStatusException.class,
                () -> service.update(target.getId(), new UserUpdateRequest(inactive.getId(), "STAFF")));
        assertNull(inactive.getAppUserId());
    }

    /** Confirms reset stores a new BCrypt hash, invalidates the old password, and audits without credentials. */
    @Test
    void shouldResetPasswordAndAuditWithoutCredentialData() {
        AppUser target = user("an.le", true, staffRole);
        target.resetPassword(encoder.encode("oldpassword"));
        when(users.findById(target.getId())).thenReturn(Optional.of(target));

        service.resetPassword(target.getId(), new UserPasswordResetRequest("newpassword", "newpassword"));

        assertFalse(encoder.matches("oldpassword", target.getPasswordHash()));
        assertTrue(encoder.matches("newpassword", target.getPasswordHash()));
        AuditLog log = savedAudit();
        assertAudit(log, "USER_PASSWORD_RESET", target.getId(), null, null);
    }

    /** Confirms a reset with a mismatched or short password is rejected without changing the hash. */
    @Test
    void shouldRejectInvalidPasswordReset() {
        AppUser target = user("an.le", true, staffRole);
        String hash = target.getPasswordHash();
        when(users.findById(target.getId())).thenReturn(Optional.of(target));

        assertThrows(ResponseStatusException.class,
                () -> service.resetPassword(target.getId(), new UserPasswordResetRequest("newpassword", "different1")));
        assertThrows(ResponseStatusException.class,
                () -> service.resetPassword(target.getId(), new UserPasswordResetRequest("short", "short")));
        assertEquals(hash, target.getPasswordHash());
        verify(audits, never()).save(any());
    }

    /** Confirms an administrator may reset their own password. */
    @Test
    void shouldAllowSelfPasswordReset() {
        AppUser self = user("admin", true, adminRole);
        setId(self, actorId);
        when(users.findById(actorId)).thenReturn(Optional.of(self));

        service.resetPassword(actorId, new UserPasswordResetRequest("newpassword", "newpassword"));

        assertTrue(encoder.matches("newpassword", self.getPasswordHash()));
    }

    /** Confirms deactivation keeps roles and Staff link, never touches Staff, and audits USER_DEACTIVATE. */
    @Test
    void shouldDeactivateWithoutDeactivatingStaffAndAudit() {
        AppUser target = user("an.le", true, staffRole);
        Staff staff = staff("STF-000001", true);
        staff.linkAppUser(target.getId());
        when(users.findById(target.getId())).thenReturn(Optional.of(target));
        when(staffRepository.findByAppUserId(target.getId())).thenReturn(Optional.of(staff));

        service.deactivate(target.getId());

        assertFalse(target.isActive());
        assertEquals(1, target.getRoles().size());
        assertTrue(staff.isActive());
        assertEquals(target.getId(), staff.getAppUserId());
        assertAudit(savedAudit(), "USER_DEACTIVATE", target.getId(), "ACTIVE", "INACTIVE");
        verify(users, never()).delete(any(AppUser.class));
    }

    /** Confirms self-deactivation and deactivating the last active ADMIN are rejected. */
    @Test
    void shouldRejectSelfDeactivationAndLastAdminDeactivation() {
        AppUser self = user("admin", true, adminRole);
        setId(self, actorId);
        when(users.findById(actorId)).thenReturn(Optional.of(self));
        assertThrows(ResponseStatusException.class, () -> service.deactivate(actorId));
        assertTrue(self.isActive());

        AppUser lastAdmin = user("admin2", true, adminRole);
        when(users.findById(lastAdmin.getId())).thenReturn(Optional.of(lastAdmin));
        when(users.lockActiveAdminIds()).thenReturn(List.of(lastAdmin.getId()));
        assertThrows(ResponseStatusException.class, () -> service.deactivate(lastAdmin.getId()));
        assertTrue(lastAdmin.isActive());
        verify(users).lockActiveAdminIds();

        when(users.lockActiveAdminIds()).thenReturn(List.of(lastAdmin.getId(), UUID.randomUUID()));
        service.deactivate(lastAdmin.getId());
        assertFalse(lastAdmin.isActive());
    }

    /** Confirms activation audits USER_ACTIVATE and is refused while the linked Staff is inactive. */
    @Test
    void shouldActivateAndRefuseWhenLinkedStaffInactive() {
        AppUser target = user("an.le", false, staffRole);
        Staff inactiveStaff = staff("STF-000001", false);
        inactiveStaff.linkAppUser(target.getId());
        when(users.findById(target.getId())).thenReturn(Optional.of(target));
        when(staffRepository.findByAppUserId(target.getId())).thenReturn(Optional.of(inactiveStaff));

        assertThrows(ResponseStatusException.class, () -> service.activate(target.getId()));
        assertFalse(target.isActive());

        when(staffRepository.findByAppUserId(target.getId())).thenReturn(Optional.empty());
        service.activate(target.getId());
        assertTrue(target.isActive());
        assertAudit(savedAudit(), "USER_ACTIVATE", target.getId(), "INACTIVE", "ACTIVE");
    }

    /** Confirms Staff deactivation deactivates its linked active account with an audit entry. */
    @Test
    void shouldDeactivateLinkedAccountWithStaffAndAudit() {
        AppUser target = user("an.le", true, staffRole);
        Staff staff = staff("STF-000001", false);
        staff.linkAppUser(target.getId());
        when(users.findById(target.getId())).thenReturn(Optional.of(target));

        service.deactivateLinkedAccount(staff, actorId);

        assertFalse(target.isActive());
        assertAudit(savedAudit(), "USER_DEACTIVATE", target.getId(), "ACTIVE", "INACTIVE (Staff deactivated)");
    }

    /** Confirms Staff deactivation is refused when it would deactivate the last active ADMIN. */
    @Test
    void shouldRefuseStaffCascadeThatWouldRemoveLastActiveAdmin() {
        AppUser lastAdmin = user("admin2", true, adminRole);
        Staff staff = staff("STF-000001", false);
        staff.linkAppUser(lastAdmin.getId());
        when(users.findById(lastAdmin.getId())).thenReturn(Optional.of(lastAdmin));
        when(users.lockActiveAdminIds()).thenReturn(List.of(lastAdmin.getId()));

        assertThrows(ResponseStatusException.class, () -> service.deactivateLinkedAccount(staff, actorId));
        assertTrue(lastAdmin.isActive());
    }

    /** Confirms nothing happens for Staff without an account or with an already-inactive account. */
    @Test
    void shouldIgnoreStaffWithoutActiveAccount() {
        Staff unlinked = staff("STF-000001", false);
        service.deactivateLinkedAccount(unlinked, actorId);

        AppUser inactive = user("gone", false, staffRole);
        Staff linked = staff("STF-000002", false);
        linked.linkAppUser(inactive.getId());
        when(users.findById(inactive.getId())).thenReturn(Optional.of(inactive));
        service.deactivateLinkedAccount(linked, actorId);

        verify(audits, never()).save(any());
    }

    private void assertRejected(UserCreateRequest request, String expectedFragment) {
        ResponseStatusException exception = assertThrows(ResponseStatusException.class, () -> service.create(request));
        assertTrue(exception.getReason().contains(expectedFragment), exception.getReason());
    }

    private AuditLog savedAudit() {
        ArgumentCaptor<AuditLog> captor = ArgumentCaptor.forClass(AuditLog.class);
        verify(audits).save(captor.capture());
        return captor.getValue();
    }

    private void assertAudit(AuditLog log, String action, UUID target, String oldValue, String newValue) {
        assertEquals(action, read(log, "action"));
        assertEquals("APP_USER", read(log, "entityType"));
        assertEquals(target, read(log, "entityId"));
        assertEquals(oldValue, read(log, "oldValue"));
        assertEquals(newValue, read(log, "newValue"));
        assertEquals(actorId, read(log, "userId"));
    }

    private Object read(Object target, String field) {
        try {
            Field declared = target.getClass().getDeclaredField(field);
            declared.setAccessible(true);
            return declared.get(target);
        } catch (ReflectiveOperationException exception) {
            throw new IllegalStateException(exception);
        }
    }

    private Role role(String code) throws Exception {
        var constructor = Role.class.getDeclaredConstructor();
        constructor.setAccessible(true);
        Role role = constructor.newInstance();
        set(role, "code", code);
        set(role, "id", UUID.randomUUID());
        return role;
    }

    private AppUser user(String username, boolean active, Role role) {
        AppUser user = new AppUser(UUID.randomUUID(), username, encoder.encode("initialpass"));
        user.replaceRoles(role);
        if (!active) {
            user.deactivate();
        }
        return user;
    }

    private void setId(AppUser user, UUID id) {
        set(user, "id", id);
    }

    private Staff staff(String code, boolean active) {
        Staff staff = Staff.create(UUID.randomUUID(), code, "An", "Le", null, null, null, LocalDate.of(2026, 1, 1), null);
        if (!active) {
            staff.deactivate();
        }
        return staff;
    }

    private void set(Object target, String field, Object value) {
        try {
            Field declared = target.getClass().getDeclaredField(field);
            declared.setAccessible(true);
            declared.set(target, value);
        } catch (ReflectiveOperationException exception) {
            throw new IllegalStateException(exception);
        }
    }
}
