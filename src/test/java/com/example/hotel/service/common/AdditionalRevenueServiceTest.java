package com.example.hotel.service.common;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

import com.example.hotel.dto.common.request.AdditionalRevenueCreateRequest;
import com.example.hotel.dto.common.request.AdditionalRevenueSearchCriteria;
import com.example.hotel.dto.common.request.AdditionalRevenueUpdateRequest;
import com.example.hotel.entity.common.AdditionalRevenue;
import com.example.hotel.entity.common.AdditionalRevenueCategory;
import com.example.hotel.entity.common.AdditionalRevenuePaymentMethod;
import com.example.hotel.entity.common.AdditionalRevenueStatus;
import com.example.hotel.mapper.common.AdditionalRevenueMapper;
import com.example.hotel.repository.common.AdditionalRevenueCategoryRepository;
import com.example.hotel.repository.common.AdditionalRevenueRepository;
import com.example.hotel.security.CurrentUser;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.Mockito;
import org.mockito.ArgumentCaptor;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.server.ResponseStatusException;

/** Verifies Additional Revenue creation, category eligibility, and terminal void behavior. */
class AdditionalRevenueServiceTest {

    private static final UUID CATEGORY_ID = UUID.fromString("22222222-2222-2222-2222-222222222222");
    private static final UUID USER_ID = UUID.fromString("33333333-3333-3333-3333-333333333333");
    private static final Instant VOID_INSTANT = Instant.parse("2026-09-15T10:15:00Z");

    @AfterEach
    void clearSecurityContext() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void shouldCreateRecordedVndRevenueWithTrimmedDescription() {
        Fixture fixture = fixture(true);
        setCurrentUser();
        when(fixture.categoryRepository.findById(CATEGORY_ID)).thenReturn(Optional.of(fixture.category));
        when(fixture.revenueRepository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));

        var response = fixture.service.create(request("  Cart rental  "));

        assertEquals("RECORDED", response.status());
        assertEquals("VND", response.currency());
        assertEquals("Cart rental", response.description());
        assertNull(response.voidReason());
        assertNull(response.voidedAt());
    }

    @Test
    void shouldRejectInactiveCategoryAndNonPositiveAmountOnCreate() {
        Fixture inactiveFixture = fixture(false);
        setCurrentUser();
        when(inactiveFixture.categoryRepository.findById(CATEGORY_ID)).thenReturn(Optional.of(inactiveFixture.category));
        assertThrows(ResponseStatusException.class, () -> inactiveFixture.service.create(request(null)));

        Fixture activeFixture = fixture(true);
        assertThrows(ResponseStatusException.class, () -> activeFixture.service.create(new AdditionalRevenueCreateRequest(
                CATEGORY_ID, BigDecimal.ZERO, LocalDate.of(2026, 9, 15), AdditionalRevenuePaymentMethod.CASH, null)));
    }

    /**
     * Confirms an Additional Revenue amount carrying fractional dong is rejected rather than silently
     * rounded: Additional Revenue is VND-only, so its amount must be a whole number of dong.
     */
    @ParameterizedTest
    @ValueSource(strings = {"150000.5", "0.5", "100.123456"})
    void shouldRejectAmountExceedingVndPrecision(String amount) {
        Fixture fixture = fixture(true);
        setCurrentUser();
        when(fixture.categoryRepository.findById(CATEGORY_ID)).thenReturn(Optional.of(fixture.category));

        ResponseStatusException exception = assertThrows(
                ResponseStatusException.class,
                () -> fixture.service.create(new AdditionalRevenueCreateRequest(
                        CATEGORY_ID, new BigDecimal(amount), LocalDate.of(2026, 9, 15),
                        AdditionalRevenuePaymentMethod.CASH, null)));

        assertEquals(400, exception.getStatusCode().value());
    }

    @Test
    void shouldVoidRecordedRevenueUsingInjectedClockAndRejectRepeatedVoid() {
        Fixture fixture = fixture(true);
        setCurrentUser();
        AdditionalRevenue revenue = AdditionalRevenue.create(
                fixture.category, new BigDecimal("150000"), LocalDate.of(2026, 9, 15), AdditionalRevenuePaymentMethod.CASH, null);
        revenue.audit(USER_ID);
        when(fixture.revenueRepository.findByIdForUpdate(revenue.getId())).thenReturn(Optional.of(revenue));
        when(fixture.revenueRepository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));

        var response = fixture.service.voidRevenue(revenue.getId(), "  Duplicate entry  ");

        assertEquals(AdditionalRevenueStatus.VOIDED, revenue.getStatus());
        assertEquals("Duplicate entry", response.voidReason());
        assertEquals(VOID_INSTANT, response.voidedAt());
        assertEquals(USER_ID, response.voidedBy());
        assertThrows(ResponseStatusException.class, () -> fixture.service.voidRevenue(revenue.getId(), "Again"));
    }

    @Test
    void shouldRejectBlankVoidReason() {
        Fixture fixture = fixture(true);
        setCurrentUser();
        assertThrows(ResponseStatusException.class, () -> fixture.service.voidRevenue(UUID.randomUUID(), "   "));
    }

    @Test
    void shouldAllowKeepingCurrentInactiveCategoryButRejectAnotherInactiveCategoryOnUpdate() {
        Fixture fixture = fixture(false);
        setCurrentUser();
        AdditionalRevenue revenue = AdditionalRevenue.create(
                fixture.category, new BigDecimal("150000"), LocalDate.of(2026, 9, 15), AdditionalRevenuePaymentMethod.CASH, null);
        revenue.audit(USER_ID);
        when(fixture.revenueRepository.findByIdForUpdate(revenue.getId())).thenReturn(Optional.of(revenue));
        when(fixture.revenueRepository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));
        when(fixture.categoryRepository.findById(CATEGORY_ID)).thenReturn(Optional.of(fixture.category));

        var response = fixture.service.update(revenue.getId(), updateRequest(CATEGORY_ID));

        assertEquals("RECORDED", response.status());

        UUID otherInactiveCategoryId = UUID.randomUUID();
        AdditionalRevenueCategory otherInactive = Mockito.mock(AdditionalRevenueCategory.class);
        when(otherInactive.getId()).thenReturn(otherInactiveCategoryId);
        when(otherInactive.isActive()).thenReturn(false);
        when(fixture.categoryRepository.findById(otherInactiveCategoryId)).thenReturn(Optional.of(otherInactive));
        assertThrows(ResponseStatusException.class, () -> fixture.service.update(revenue.getId(), updateRequest(otherInactiveCategoryId)));
    }

    @Test
    void shouldUseTwentyRowDatabasePageWithDeterministicRevenueDateSort() {
        Fixture fixture = fixture(true);
        AdditionalRevenue revenue = AdditionalRevenue.create(
                fixture.category, new BigDecimal("150000"), LocalDate.of(2026, 9, 15), AdditionalRevenuePaymentMethod.CASH, null);
        when(fixture.revenueRepository.findAll(any(org.springframework.data.jpa.domain.Specification.class), any(Pageable.class)))
                .thenReturn(new PageImpl<>(java.util.List.of(revenue)));

        fixture.service.findPage(new AdditionalRevenueSearchCriteria(), 1);

        ArgumentCaptor<Pageable> pageableCaptor = ArgumentCaptor.forClass(Pageable.class);
        org.mockito.Mockito.verify(fixture.revenueRepository)
                .findAll(any(org.springframework.data.jpa.domain.Specification.class), pageableCaptor.capture());
        Pageable pageable = pageableCaptor.getValue();
        assertEquals(1, pageable.getPageNumber());
        assertEquals(20, pageable.getPageSize());
        assertEquals("revenueDate: DESC,id: DESC", pageable.getSort().toString());
    }

    private Fixture fixture(boolean active) {
        AdditionalRevenueRepository revenueRepository = Mockito.mock(AdditionalRevenueRepository.class);
        AdditionalRevenueCategoryRepository categoryRepository = Mockito.mock(AdditionalRevenueCategoryRepository.class);
        AdditionalRevenueCategory category = Mockito.mock(AdditionalRevenueCategory.class);
        when(category.getId()).thenReturn(CATEGORY_ID);
        when(category.getCode()).thenReturn("ELECTRIC_CART_RENTAL");
        when(category.getName()).thenReturn("Electric Cart Rental");
        when(category.isActive()).thenReturn(active);
        Clock clock = Clock.fixed(VOID_INSTANT, ZoneId.of("Asia/Ho_Chi_Minh"));
        return new Fixture(
                new AdditionalRevenueService(revenueRepository, categoryRepository, new AdditionalRevenueMapper(), clock),
                revenueRepository,
                categoryRepository,
                category);
    }

    private AdditionalRevenueCreateRequest request(String description) {
        return new AdditionalRevenueCreateRequest(
                CATEGORY_ID, new BigDecimal("150000"), LocalDate.of(2026, 9, 15), AdditionalRevenuePaymentMethod.CASH, description);
    }

    private AdditionalRevenueUpdateRequest updateRequest(UUID categoryId) {
        return new AdditionalRevenueUpdateRequest(
                categoryId, new BigDecimal("160000"), LocalDate.of(2026, 9, 16), AdditionalRevenuePaymentMethod.CASH, null);
    }

    private void setCurrentUser() {
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(new CurrentUser(USER_ID, "manager"), null));
    }

    private record Fixture(
            AdditionalRevenueService service,
            AdditionalRevenueRepository revenueRepository,
            AdditionalRevenueCategoryRepository categoryRepository,
            AdditionalRevenueCategory category) {}
}
