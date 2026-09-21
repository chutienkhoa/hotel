package com.example.hotel.security;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.example.hotel.config.SecurityConfig;
import com.example.hotel.controller.common.DashboardPageController;
import com.example.hotel.dto.common.request.RolePermissionUpdateRequest;
import com.example.hotel.dto.common.response.DashboardResponse;
import com.example.hotel.entity.common.AppUser;
import com.example.hotel.entity.common.Permission;
import com.example.hotel.entity.common.Role;
import com.example.hotel.repository.common.AppUserRepository;
import com.example.hotel.repository.common.AuditLogRepository;
import com.example.hotel.repository.common.PermissionRepository;
import com.example.hotel.repository.common.RoleRepository;
import com.example.hotel.service.common.DashboardService;
import com.example.hotel.service.common.RolePermissionService;
import java.lang.reflect.Field;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

/**
 * Proves a permission change on the SAME role, saved through the real {@link RolePermissionService},
 * takes effect on the very next request for both an existing MVC session and an existing JWT, with no
 * new login or token.
 */
@WebMvcTest(DashboardPageController.class)
@Import({SecurityConfig.class, SessionUserDetailsService.class})
class PermissionChangeRefreshTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private DashboardService dashboardService;

    @MockitoBean
    private AppUserRepository appUserRepository;

    @MockitoBean
    private JwtService jwtService;

    /** Clears the security context. */
    @AfterEach
    void clear() {
        SecurityContextHolder.clearContext();
    }

    /** Confirms an existing MVC session loses a permission removed from its role on the next request. */
    @Test
    void shouldApplyPermissionRemovalToExistingMvcSessionOnNextRequest() throws Exception {
        Fixture fixture = fixture();
        when(appUserRepository.findById(fixture.user.getId())).thenReturn(Optional.of(fixture.user));
        when(dashboardService.getDashboard()).thenReturn(mock(DashboardResponse.class));
        var session = new UsernamePasswordAuthenticationToken(
                new SessionUserPrincipal(fixture.user.getId(), "an.le", "hash",
                        List.of(new SimpleGrantedAuthority("PERM_VIEW_REPORT"))),
                null, List.of(new SimpleGrantedAuthority("PERM_VIEW_REPORT")));

        mockMvc.perform(get("/dashboard").with(authentication(session))).andExpect(status().isOk());

        fixture.actAsAdmin();
        fixture.service.update(fixture.requestWithout("VIEW_REPORT"));

        mockMvc.perform(get("/dashboard").with(authentication(session))).andExpect(status().isForbidden());
    }

    /** Confirms an existing JWT loses the permission on the next request, and regains it when re-granted. */
    @Test
    void shouldApplyPermissionChangeToExistingJwtOnNextRequest() throws Exception {
        Fixture fixture = fixture();
        JwtService realJwt = new JwtService("test-secret-test-secret-test-secret-test-secret", Duration.ofHours(1));
        String token = realJwt.issue(fixture.user, List.of("VIEW_REPORT"));
        when(appUserRepository.findById(fixture.user.getId())).thenReturn(Optional.of(fixture.user));
        JwtFilter filter = new JwtFilter(realJwt, appUserRepository);

        assertTrue(authoritiesAfter(filter, token).contains("PERM_VIEW_REPORT"));

        fixture.actAsAdmin();
        fixture.service.update(fixture.requestWithout("VIEW_REPORT"));
        assertFalse(authoritiesAfter(filter, token).contains("PERM_VIEW_REPORT"));
        assertTrue(authoritiesAfter(filter, token).contains("PERM_VIEW_BOOKING"));

        fixture.actAsAdmin();
        fixture.service.update(fixture.requestWith("VIEW_REPORT"));
        assertTrue(authoritiesAfter(filter, token).contains("PERM_VIEW_REPORT"));
    }

    private List<String> authoritiesAfter(JwtFilter filter, String token) throws Exception {
        SecurityContextHolder.clearContext();
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader("Authorization", "Bearer " + token);
        filter.doFilter(request, new MockHttpServletResponse(), new MockFilterChain());
        var authentication = SecurityContextHolder.getContext().getAuthentication();
        assertNotNull(authentication);
        return authentication.getAuthorities().stream().map(a -> a.getAuthority()).toList();
    }

    private Fixture fixture() throws Exception {
        Permission viewReport = permission("VIEW_REPORT");
        Permission manageUser = permission("MANAGE_USER");
        Permission others = permission("VIEW_BOOKING");
        Role admin = role("ADMIN", viewReport, manageUser, others);
        Role manager = role("MANAGER", viewReport, others);
        Role staff = role("STAFF", others);
        AppUser user = new AppUser(UUID.randomUUID(), "an.le", "hash");
        user.replaceRoles(admin);
        RoleRepository roles = mock(RoleRepository.class);
        PermissionRepository permissions = mock(PermissionRepository.class);
        when(roles.findByCodeIn(any())).thenReturn(List.of(admin, manager, staff));
        when(permissions.findByCodeIn(any())).thenAnswer(invocation -> {
            List<Permission> all = new ArrayList<>();
            for (String code : (Iterable<String>) invocation.getArgument(0)) {
                all.add(code.equals("VIEW_REPORT") ? viewReport : code.equals("MANAGE_USER") ? manageUser
                        : code.equals("VIEW_BOOKING") ? others : permissionUnchecked(code));
            }
            return all;
        });
        return new Fixture(user, new RolePermissionService(roles, permissions, mock(AuditLogRepository.class)));
    }

    private record Fixture(AppUser user, RolePermissionService service) {

        void actAsAdmin() {
            SecurityContextHolder.getContext().setAuthentication(
                    new UsernamePasswordAuthenticationToken(new CurrentUser(UUID.randomUUID(), "admin"), null));
        }

        RolePermissionUpdateRequest requestWithout(String removed) {
            return request(removed, false);
        }

        RolePermissionUpdateRequest requestWith(String added) {
            return request(added, true);
        }

        private RolePermissionUpdateRequest request(String code, boolean present) {
            RolePermissionUpdateRequest request = new RolePermissionUpdateRequest();
            request.setSubmittedRoles(new ArrayList<>(List.of("ADMIN", "MANAGER", "STAFF")));
            List<String> admin = new ArrayList<>(List.of("VIEW_BOOKING"));
            if (present) {
                admin.add(code);
            }
            request.setGrants(Map.of("ADMIN", admin, "MANAGER", new ArrayList<>(List.of("VIEW_REPORT", "VIEW_BOOKING")),
                    "STAFF", new ArrayList<>(List.of("VIEW_BOOKING"))));
            return request;
        }
    }

    private static Permission permissionUnchecked(String code) {
        try {
            return permission(code);
        } catch (Exception exception) {
            throw new IllegalStateException(exception);
        }
    }

    private static Permission permission(String code) throws Exception {
        var constructor = Permission.class.getDeclaredConstructor();
        constructor.setAccessible(true);
        Permission permission = constructor.newInstance();
        set(permission, "code", code);
        return permission;
    }

    private static Role role(String code, Permission... permissions) throws Exception {
        var constructor = Role.class.getDeclaredConstructor();
        constructor.setAccessible(true);
        Role role = constructor.newInstance();
        set(role, "code", code);
        set(role, "id", UUID.randomUUID());
        role.getPermissions().addAll(List.of(permissions));
        return role;
    }

    private static void set(Object target, String field, Object value) throws Exception {
        Field declared = target.getClass().getDeclaredField(field);
        declared.setAccessible(true);
        declared.set(target, value);
    }
}
