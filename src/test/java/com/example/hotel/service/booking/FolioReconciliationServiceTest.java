package com.example.hotel.service.booking;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.example.hotel.dto.booking.response.FolioReconciliationResponse;
import com.example.hotel.dto.booking.response.FolioReconciliationResponse.IssueType;
import com.example.hotel.entity.booking.Charge;
import com.example.hotel.entity.booking.ChargeType;
import com.example.hotel.entity.booking.Reservation;
import com.example.hotel.entity.booking.Stay;
import com.example.hotel.entity.common.AdditionalRevenue;
import com.example.hotel.entity.common.AdditionalRevenueCategory;
import com.example.hotel.repository.booking.ChargeRepository;
import com.example.hotel.repository.booking.StayExtensionRoomRepository;
import com.example.hotel.repository.booking.StayRepository;
import com.example.hotel.repository.common.AdditionalRevenueRepository;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * Verifies FolioReconciliationService's Charge-void-aware service-revenue check: a correctly
 * voided pair (VOIDED Charge + VOIDED linked Additional Revenue) reports no issue, while a genuinely
 * diverged pair (Charge and its linked revenue disagree on status) is still reported.
 */
class FolioReconciliationServiceTest {

    private static final UUID STAY_ID = UUID.randomUUID();

    private final StayRepository stays = mock(StayRepository.class);
    private final ChargeRepository charges = mock(ChargeRepository.class);
    private final StayExtensionRoomRepository extensionRooms = mock(StayExtensionRoomRepository.class);
    private final AdditionalRevenueRepository additionalRevenues = mock(AdditionalRevenueRepository.class);

    private FolioReconciliationService service() {
        return new FolioReconciliationService(stays, charges, extensionRooms, additionalRevenues);
    }

    /** Builds a Stay whose Reservation has no booked rooms, so only the service-revenue check is exercised. */
    private Stay stayWithNoBookedRooms() {
        Stay stay = mock(Stay.class);
        Reservation reservation = new Reservation(
                UUID.randomUUID(), "R20260920-000001", null,
                LocalDate.of(2026, 9, 20), LocalDate.of(2026, 9, 22), "VND", null);
        when(stay.getReservation()).thenReturn(reservation);
        when(stays.findById(STAY_ID)).thenReturn(Optional.of(stay));
        when(extensionRooms.findByStayIdWithCharge(STAY_ID)).thenReturn(List.of());
        return stay;
    }

    private AdditionalRevenueCategory category() {
        return AdditionalRevenueCategory.create("GUEST_SERVICE", "Guest Service", null);
    }

    /** Confirms an ACTIVE service Charge with a RECORDED, amount-matching linked revenue is healthy (unchanged behavior). */
    @Test
    void shouldReportNoIssueForActiveChargeWithRecordedRevenue() {
        Stay stay = stayWithNoBookedRooms();
        Charge charge = Charge.create(stay, ChargeType.SERVICE, null, null, null, BigDecimal.TEN);
        AdditionalRevenue revenue = AdditionalRevenue.createFromCharge(category(), charge, LocalDate.of(2026, 9, 20), "d");
        when(charges.findByStayIdWithSource(STAY_ID)).thenReturn(List.of(charge));
        when(additionalRevenues.findByChargeIdIn(List.of(charge.getId()))).thenReturn(List.of(revenue));

        FolioReconciliationResponse response = service().reconcile(STAY_ID);

        assertEquals("MATCHED", response.status());
        assertTrue(response.issues().isEmpty());
    }

    /** Confirms a VOIDED service Charge with a VOIDED linked revenue (correctly voided pair) is healthy — no false mismatch. */
    @Test
    void shouldReportNoIssueForCorrectlyVoidedChargeAndRevenuePair() {
        Stay stay = stayWithNoBookedRooms();
        Charge charge = Charge.create(stay, ChargeType.SERVICE, null, null, null, BigDecimal.TEN);
        charge.voidCharge("mistake");
        AdditionalRevenue revenue = AdditionalRevenue.createFromCharge(category(), charge, LocalDate.of(2026, 9, 20), "d");
        revenue.voidForChargeCorrection("mistake", Instant.now(), UUID.randomUUID());
        when(charges.findByStayIdWithSource(STAY_ID)).thenReturn(List.of(charge));
        when(additionalRevenues.findByChargeIdIn(List.of(charge.getId()))).thenReturn(List.of(revenue));

        FolioReconciliationResponse response = service().reconcile(STAY_ID);

        assertEquals("MATCHED", response.status());
        assertTrue(response.issues().isEmpty());
    }

    /** Confirms a VOIDED Charge whose linked revenue is still RECORDED (a genuine inconsistency) is still detected. */
    @Test
    void shouldReportMismatchWhenVoidedChargeStillHasRecordedRevenue() {
        Stay stay = stayWithNoBookedRooms();
        Charge charge = Charge.create(stay, ChargeType.SERVICE, null, null, null, BigDecimal.TEN);
        charge.voidCharge("mistake");
        AdditionalRevenue revenue = AdditionalRevenue.createFromCharge(category(), charge, LocalDate.of(2026, 9, 20), "d");
        // revenue intentionally left RECORDED: the atomic void did not happen (simulated inconsistency)
        when(charges.findByStayIdWithSource(STAY_ID)).thenReturn(List.of(charge));
        when(additionalRevenues.findByChargeIdIn(List.of(charge.getId()))).thenReturn(List.of(revenue));

        FolioReconciliationResponse response = service().reconcile(STAY_ID);

        assertEquals("MISMATCH", response.status());
        assertEquals(1, response.issues().size());
        assertEquals(IssueType.SERVICE_REVENUE_AMOUNT_MISMATCH, response.issues().get(0).type());
    }

    /** Confirms an ACTIVE Charge whose linked revenue was (incorrectly) VOIDED independently is still detected. */
    @Test
    void shouldReportMismatchWhenActiveChargeHasVoidedRevenue() {
        Stay stay = stayWithNoBookedRooms();
        Charge charge = Charge.create(stay, ChargeType.SERVICE, null, null, null, BigDecimal.TEN);
        AdditionalRevenue revenue = AdditionalRevenue.createFromCharge(category(), charge, LocalDate.of(2026, 9, 20), "d");
        revenue.voidForChargeCorrection("mistake", Instant.now(), UUID.randomUUID());
        when(charges.findByStayIdWithSource(STAY_ID)).thenReturn(List.of(charge));
        when(additionalRevenues.findByChargeIdIn(List.of(charge.getId()))).thenReturn(List.of(revenue));

        FolioReconciliationResponse response = service().reconcile(STAY_ID);

        assertEquals("MISMATCH", response.status());
        assertEquals(1, response.issues().size());
        assertEquals(IssueType.SERVICE_REVENUE_AMOUNT_MISMATCH, response.issues().get(0).type());
    }
}
