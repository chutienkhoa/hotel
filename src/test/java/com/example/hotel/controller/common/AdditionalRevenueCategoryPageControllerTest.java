package com.example.hotel.controller.common;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.example.hotel.dto.common.response.AdditionalRevenueCategoryResponse;
import com.example.hotel.security.JwtService;
import com.example.hotel.service.common.AdditionalRevenueCategoryService;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Import;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

/** Verifies Additional Revenue Category Management MVC authorization and rendering. */
@WebMvcTest({AdditionalRevenueCategoryPageController.class, AdditionalRevenuePageController.class})
@Import(AdditionalRevenueCategoryPageControllerTest.MethodSecurityTestConfiguration.class)
class AdditionalRevenueCategoryPageControllerTest {

    private static final UUID CATEGORY_ID = UUID.fromString("22222222-2222-2222-2222-222222222222");

    @Autowired private MockMvc mockMvc;
    @MockitoBean private AdditionalRevenueCategoryService categoryService;
    @MockitoBean private com.example.hotel.service.common.AdditionalRevenueService revenueService;
    @MockitoBean private JwtService jwtService;

    @Test
    void shouldAllowAdminAndManagerAndRejectStaff() throws Exception {
        when(categoryService.findAll()).thenReturn(List.of(activeCategory()));
        for (String username : List.of("admin", "manager")) {
            mockMvc.perform(get("/additional-revenue-categories").with(user(username).authorities(manageRevenue())))
                    .andExpect(status().isOk());
            mockMvc.perform(post("/additional-revenue-categories").param("code", "AIRPORT_TRANSFER").param("name", "Airport Transfer")
                            .with(user(username).authorities(manageRevenue())).with(csrf()))
                    .andExpect(status().is3xxRedirection());
        }
        mockMvc.perform(get("/additional-revenue-categories").with(user("staff").authorities(staffAuthorities())))
                .andExpect(status().isForbidden());
    }

    @Test
    void shouldRenderLifecycleActionsAndReadOnlyCode() throws Exception {
        when(categoryService.findAll()).thenReturn(List.of(activeCategory(), inactiveCategory()));
        when(categoryService.findById(CATEGORY_ID)).thenReturn(activeCategory());

        mockMvc.perform(get("/additional-revenue-categories").with(user("admin").authorities(manageRevenue())))
                .andExpect(status().isOk()).andExpect(content().string(containsString(">Deactivate</button>")))
                .andExpect(content().string(containsString(">Reactivate</button>")));
        mockMvc.perform(get("/additional-revenue-categories/{id}/edit", CATEGORY_ID).with(user("admin").authorities(manageRevenue())))
                .andExpect(status().isOk()).andExpect(content().string(containsString("disabled")))
                .andExpect(content().string(not(containsString("name=\"code\""))));
    }

    @Test
    void shouldNotExposeDeleteAndShouldLinkFromRevenueList() throws Exception {
        mockMvc.perform(delete("/additional-revenue-categories/{id}", CATEGORY_ID).with(user("admin").authorities(manageRevenue())).with(csrf()))
                .andExpect(status().isMethodNotAllowed());
        when(revenueService.findAllCategories()).thenReturn(List.of(activeCategory()));
        when(revenueService.findPage(any(), org.mockito.ArgumentMatchers.eq(0))).thenReturn(new org.springframework.data.domain.PageImpl<>(List.of()));
        mockMvc.perform(get("/additional-revenues").with(user("admin").authorities(manageRevenue())))
                .andExpect(status().isOk()).andExpect(content().string(containsString("href=\"/additional-revenue-categories\">Manage Categories</a>")));
    }

    private static List<SimpleGrantedAuthority> manageRevenue() { return List.of(new SimpleGrantedAuthority("PERM_MANAGE_ADDITIONAL_REVENUE")); }
    private static List<SimpleGrantedAuthority> staffAuthorities() { return List.of(new SimpleGrantedAuthority("PERM_VIEW_BOOKING")); }
    private static AdditionalRevenueCategoryResponse activeCategory() { return new AdditionalRevenueCategoryResponse(CATEGORY_ID, "ELECTRIC_CART_RENTAL", "Electric Cart Rental", null, true); }
    private static AdditionalRevenueCategoryResponse inactiveCategory() { return new AdditionalRevenueCategoryResponse(UUID.randomUUID(), "OLD_CATEGORY", "Old category", null, false); }

    @TestConfiguration @EnableMethodSecurity static class MethodSecurityTestConfiguration {}
}
