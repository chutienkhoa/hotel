package com.example.hotel.service.booking;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.example.hotel.dto.booking.request.ChargeCreateRequest;
import com.example.hotel.entity.booking.Charge;
import com.example.hotel.entity.booking.ChargeType;
import com.example.hotel.entity.booking.Reservation;
import com.example.hotel.entity.booking.Stay;
import com.example.hotel.entity.booking.StayStatus;
import com.example.hotel.entity.common.AdditionalRevenue;
import com.example.hotel.entity.common.AdditionalRevenueCategory;
import com.example.hotel.exception.LocalizedResponseStatusException;
import com.example.hotel.mapper.booking.ChargeMapper;
import com.example.hotel.repository.booking.ChargeRepository;
import com.example.hotel.repository.booking.StayRepository;
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
import org.junit.jupiter.params.provider.EnumSource;
import org.mockito.ArgumentCaptor;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;

/** Verifies a guest service Charge creates exactly one linked, system-managed Additional Revenue under the Stay lock. */
class GuestServiceRevenueChargeTest {

    private static final ZoneId ZONE = ZoneId.of("Asia/Ho_Chi_Minh");

    private final ChargeRepository charges = mock(ChargeRepository.class);
    private final StayRepository stays = mock(StayRepository.class);
    private final AdditionalRevenueRepository revenues = mock(AdditionalRevenueRepository.class);
    private final AdditionalRevenueCategoryRepository categories = mock(AdditionalRevenueCategoryRepository.class);
    private final UUID stayId = UUID.randomUUID();

    @AfterEach
    void clear() {
        SecurityContextHolder.clearContext();
    }

    private ChargeService service() {
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(new CurrentUser(UUID.randomUUID(), "cashier"), null));
        when(charges.save(any(Charge.class))).thenAnswer(invocation -> invocation.getArgument(0));
        when(categories.findByCode(anyString())).thenAnswer(invocation ->
                Optional.of(AdditionalRevenueCategory.create(invocation.getArgument(0), "n", null)));
        return new ChargeService(charges, stays, new ChargeMapper(), revenues, categories,
                Clock.fixed(Instant.parse("2026-09-20T03:00:00Z"), ZONE));
    }

    private Stay stay(String currency, StayStatus status) {
        Stay stay = mock(Stay.class);
        when(stay.getId()).thenReturn(stayId);
        when(stay.getStatus()).thenReturn(status);
        when(stay.getReservation()).thenReturn(new Reservation(
                UUID.randomUUID(), "R20260920-000001", null, LocalDate.of(2026, 9, 20), LocalDate.of(2026, 9, 22), currency, null));
        when(stays.findByIdForUpdate(stayId)).thenReturn(Optional.of(stay));
        return stay;
    }

    private static ChargeCreateRequest fixed(ChargeType type, String amount) {
        return new ChargeCreateRequest(type, "Coke", null, null, new BigDecimal(amount));
    }

    /** Confirms each guest service type creates one revenue row: linked, same amount, hotel-local date, no payment method. */
    @ParameterizedTest
    @EnumSource(value = ChargeType.class, names = {"BREAKFAST", "EXTRA_BED", "LAUNDRY", "MINIBAR", "SERVICE", "OTHER"})
    void shouldCreateExactlyOneLinkedRevenueForGuestServiceCharges(ChargeType type) {
        stay("VND", StayStatus.CHECKED_IN);

        service().create(stayId, fixed(type, "150000"));

        ArgumentCaptor<AdditionalRevenue> revenue = ArgumentCaptor.forClass(AdditionalRevenue.class);
        verify(revenues).save(revenue.capture());
        assertEquals(0, new BigDecimal("150000").compareTo(revenue.getValue().getAmount()));
        assertEquals("GUEST_" + type.name(), revenue.getValue().getCategory().getCode());
        assertNull(revenue.getValue().getPaymentMethod(), "posting a Charge is not a payment");
        assertTrue(revenue.getValue().isChargeLinked());
        assertEquals("VND", revenue.getValue().getCurrency());
        assertTrue(revenue.getValue().getDescription().contains("R20260920-000001"));
    }

    /** Confirms the revenue date is the hotel-local date of chargedAt (never the JVM default zone). */
    @Test
    void shouldUseTheHotelLocalDateOfChargedAt() {
        stay("VND", StayStatus.CHECKED_IN);
        service().create(stayId, fixed(ChargeType.MINIBAR, "10"));

        ArgumentCaptor<Charge> charge = ArgumentCaptor.forClass(Charge.class);
        verify(charges).save(charge.capture());
        ArgumentCaptor<AdditionalRevenue> revenue = ArgumentCaptor.forClass(AdditionalRevenue.class);
        verify(revenues).save(revenue.capture());
        assertEquals(charge.getValue().getChargedAt().atZone(ZONE).toLocalDate(), revenue.getValue().getRevenueDate());
    }

    /** Confirms ROOM (manual) and unsupported types create neither a Charge nor a revenue row. */
    @Test
    void shouldCreateNoRevenueForRoomOrUnsupportedTypes() {
        ChargeService service = service();
        for (ChargeType type : new ChargeType[] {ChargeType.ROOM, ChargeType.TAX, ChargeType.DISCOUNT}) {
            assertThrows(org.springframework.web.server.ResponseStatusException.class,
                    () -> service.create(stayId, fixed(type, "10")));
        }
        verify(charges, never()).save(any());
        verify(revenues, never()).save(any());
    }

    /** Confirms a non-VND reservation rejects the service Charge atomically with a localizable error. */
    @Test
    void shouldRejectServiceChargeOnANonVndReservationAtomically() {
        stay("USD", StayStatus.CHECKED_IN);

        LocalizedResponseStatusException exception = assertThrows(LocalizedResponseStatusException.class,
                () -> service().create(stayId, fixed(ChargeType.LAUNDRY, "10")));

        assertEquals("payment.folio.charge.error.serviceRevenueCurrency", exception.getMessageKey());
        verify(charges, never()).save(any());
        verify(revenues, never()).save(any());
    }

    /** Confirms a missing system category rejects atomically. */
    @Test
    void shouldRejectWhenTheSystemCategoryIsMissing() {
        stay("VND", StayStatus.CHECKED_IN);
        ChargeService service = service();
        when(categories.findByCode(anyString())).thenReturn(Optional.empty());

        assertThrows(LocalizedResponseStatusException.class, () -> service.create(stayId, fixed(ChargeType.SERVICE, "10")));
        verify(charges, never()).save(any());
    }

    /** Confirms the Stay is locked before its state is validated and before anything is written. */
    @Test
    void shouldLockTheStayBeforeValidatingAndWriting() {
        stay("VND", StayStatus.CHECKED_IN);
        service().create(stayId, fixed(ChargeType.SERVICE, "10"));

        var order = inOrder(stays, charges, revenues);
        order.verify(stays).findByIdForUpdate(stayId);
        order.verify(charges).save(any());
        order.verify(revenues).save(any());
    }

    /** Confirms a checked-out Stay rejects the Charge after the lock and creates no revenue. */
    @Test
    void shouldRejectOnACheckedOutStay() {
        stay("VND", StayStatus.CHECKED_OUT);

        assertThrows(org.springframework.web.server.ResponseStatusException.class,
                () -> service().create(stayId, fixed(ChargeType.SERVICE, "10")));
        verify(revenues, never()).save(any());
    }

    /** Confirms Charge-linked revenue is system-managed: edit and void are rejected, standalone revenue is unchanged. */
    @Test
    void shouldRejectEditAndVoidOfLinkedRevenueButKeepStandaloneBehavior() {
        Charge charge = Charge.create(mock(Stay.class), ChargeType.SERVICE, "x", null, null, BigDecimal.TEN);
        AdditionalRevenueCategory category = AdditionalRevenueCategory.create("GUEST_SERVICE", "n", null);
        AdditionalRevenue linked = AdditionalRevenue.createFromCharge(category, charge, LocalDate.of(2026, 9, 20), "d");

        assertThrows(IllegalStateException.class, () -> linked.updateRecorded(category, BigDecimal.ONE, LocalDate.now(),
                com.example.hotel.entity.common.AdditionalRevenuePaymentMethod.CASH, null));
        assertThrows(IllegalStateException.class, () -> linked.voidRevenue("r", Instant.now(), UUID.randomUUID()));

        AdditionalRevenue standalone = AdditionalRevenue.create(category, BigDecimal.TEN, LocalDate.of(2026, 9, 20),
                com.example.hotel.entity.common.AdditionalRevenuePaymentMethod.CASH, null);
        standalone.updateRecorded(category, BigDecimal.ONE, LocalDate.of(2026, 9, 21),
                com.example.hotel.entity.common.AdditionalRevenuePaymentMethod.BANK_TRANSFER, "n");
        standalone.voidRevenue("wrong", Instant.now(), UUID.randomUUID());
        assertEquals(com.example.hotel.entity.common.AdditionalRevenueStatus.VOIDED, standalone.getStatus());
    }
}
