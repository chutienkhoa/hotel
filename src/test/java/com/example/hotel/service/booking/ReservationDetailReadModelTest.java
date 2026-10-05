package com.example.hotel.service.booking;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.example.hotel.common.DisplayFormats;
import com.example.hotel.dto.booking.response.CurrentRoomResponse;
import com.example.hotel.dto.booking.response.ReservationActivityEntry;
import com.example.hotel.dto.booking.response.ReservationDetailEligibility;
import com.example.hotel.dto.booking.response.ReservationDetailResponse;
import com.example.hotel.dto.booking.response.ReservationLifecycleResponse;
import com.example.hotel.dto.booking.response.RoomHistoryLineResponse;
import com.example.hotel.entity.booking.Reservation;
import com.example.hotel.entity.booking.ReservationRoom;
import com.example.hotel.entity.booking.ReservationStatus;
import com.example.hotel.entity.booking.RoomChangeReason;
import com.example.hotel.entity.booking.Stay;
import com.example.hotel.entity.booking.StayRoomAssignment;
import com.example.hotel.entity.customer.Guest;
import com.example.hotel.entity.room.Room;
import com.example.hotel.entity.room.RoomType;
import com.example.hotel.mapper.booking.ReservationMapper;
import com.example.hotel.repository.booking.ChargeRepository;
import com.example.hotel.repository.booking.PaymentRepository;
import com.example.hotel.repository.booking.StayRepository;
import com.example.hotel.repository.booking.StayRoomAssignmentRepository;
import com.example.hotel.repository.common.AppUserRepository;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.mockito.Answers;

/** Verifies the bounded read-model additions behind the shared Reservation Detail (Task33 spec 9.3.4.12). */
class ReservationDetailReadModelTest {

    private static final UUID RESERVATION_ID = UUID.randomUUID();
    private static final ZoneId ZONE = ZoneId.of("Asia/Ho_Chi_Minh");
    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-10-05T03:00:00Z"), ZONE);

    // ------------------------------------------------------------------------------------ lifecycle time / actor

    /** Confirms each lifecycle event takes the time and actor of its audit entry and absent events stay null. */
    @Test
    void shouldDeriveLifecycleEventsFromAuditEntries() {
        ReservationLifecycleResponse lifecycle = ReservationLifecycleResponse.from(List.of(
                new ReservationActivityEntry(Instant.parse("2026-10-01T01:00:00Z"), "creator", "CREATE"),
                new ReservationActivityEntry(Instant.parse("2026-10-01T02:00:00Z"), "confirmer", "CONFIRM"),
                new ReservationActivityEntry(Instant.parse("2026-10-02T02:00:00Z"), "receptionist", "CHECK_IN")));

        assertEquals("confirmer", lifecycle.confirmed().actorDisplay());
        assertEquals(Instant.parse("2026-10-02T02:00:00Z"), lifecycle.checkedIn().occurredAt());
        assertNull(lifecycle.checkedOut());
        assertNull(lifecycle.cancelled());
        assertNull(lifecycle.noShow());
    }

    /** Confirms a repeated action resolves to its latest entry, whatever order the entries arrive in. */
    @Test
    void shouldUseTheLatestEntryWhenAnActionRepeats() {
        ReservationLifecycleResponse lifecycle = ReservationLifecycleResponse.from(List.of(
                new ReservationActivityEntry(Instant.parse("2026-10-03T00:00:00Z"), "second", "CANCEL"),
                new ReservationActivityEntry(Instant.parse("2026-10-02T00:00:00Z"), "first", "CANCEL")));

        assertEquals("second", lifecycle.cancelled().actorDisplay());
    }

    /** Confirms a Reservation with no audit history yields no lifecycle events instead of failing. */
    @Test
    void shouldYieldNoLifecycleEventsForAnUnauditedReservation() {
        ReservationLifecycleResponse lifecycle = ReservationLifecycleResponse.from(List.of());

        assertNull(lifecycle.confirmed());
        assertNull(lifecycle.noShow());
    }

    // ------------------------------------------------------------------------------------ eligibility

    /** Confirms the no-show date rule: only a check-in date strictly in the past qualifies. */
    @Test
    void shouldApplyTheNoShowDateRule() {
        LocalDate today = LocalDate.of(2026, 10, 5);

        assertTrue(ReservationActionRules.noShowDateReached(today.minusDays(1), today));
        assertFalse(ReservationActionRules.noShowDateReached(today, today));
        assertFalse(ReservationActionRules.noShowDateReached(today.plusDays(1), today));
        assertTrue(ReservationActionRules.noShowEligible(ReservationStatus.CONFIRMED, today.minusDays(1), today));
        assertFalse(ReservationActionRules.noShowEligible(ReservationStatus.CHECKED_IN, today.minusDays(1), today));
    }

    /** Confirms Check-in and no-show eligibility reuse the backend rules with the hotel business date. */
    @Test
    void shouldReportEligibilityFromTheSameRulesAsTheOperations() {
        StayRepository stays = mock(StayRepository.class);
        ReservationDetailEligibilityService service = new ReservationDetailEligibilityService(stays, CLOCK);
        LocalDate today = LocalDate.now(CLOCK);

        assertEquals(new ReservationDetailEligibility(true, true),
                service.evaluate(RESERVATION_ID, ReservationStatus.CONFIRMED, today.minusDays(1)));
        assertEquals(new ReservationDetailEligibility(true, false),
                service.evaluate(RESERVATION_ID, ReservationStatus.CONFIRMED, today));
        assertEquals(ReservationDetailEligibility.NONE,
                service.evaluate(RESERVATION_ID, ReservationStatus.CONFIRMED, today.plusDays(1)));
        assertEquals(ReservationDetailEligibility.NONE,
                service.evaluate(RESERVATION_ID, ReservationStatus.CANCELLED, today.minusDays(1)));
        assertEquals(ReservationDetailEligibility.NONE,
                service.evaluate(RESERVATION_ID, ReservationStatus.DRAFT, today));
    }

    /** Confirms an existing Stay makes Check-in ineligible, exactly as the check-in operation rejects it. */
    @Test
    void shouldNotOfferCheckInWhenAStayAlreadyExists() {
        StayRepository stays = mock(StayRepository.class);
        when(stays.existsByReservationId(RESERVATION_ID)).thenReturn(true);
        ReservationDetailEligibilityService service = new ReservationDetailEligibilityService(stays, CLOCK);

        assertFalse(service.evaluate(RESERVATION_ID, ReservationStatus.CONFIRMED, LocalDate.now(CLOCK)).checkInEligible());
    }

    // ------------------------------------------------------------------------------------ charge split

    /** Confirms the Room Charges / Additional Charges split reads the ACTIVE ROOM and non-ROOM totals. */
    @Test
    void shouldSplitChargesIntoRoomAndAdditional() {
        UUID stayId = UUID.randomUUID();
        ChargeRepository charges = mock(ChargeRepository.class);
        when(charges.sumRoomAmountByStayId(stayId)).thenReturn(new BigDecimal("3600000"));
        when(charges.sumAdditionalAmountByStayId(stayId)).thenReturn(new BigDecimal("350000"));

        StayChargeBreakdown breakdown =
                new StayBalanceService(charges, mock(PaymentRepository.class)).chargeBreakdown(stayId);

        assertEquals(new BigDecimal("3600000"), breakdown.roomCharges());
        assertEquals(new BigDecimal("350000"), breakdown.additionalCharges());
    }

    /** Confirms a Stay with no Charges yields zero parts rather than a null. */
    @Test
    void shouldTreatMissingChargeTotalsAsZero() {
        StayBalanceService service = new StayBalanceService(mock(ChargeRepository.class), mock(PaymentRepository.class));

        StayChargeBreakdown breakdown = service.chargeBreakdown(UUID.randomUUID());

        assertEquals(BigDecimal.ZERO, breakdown.roomCharges());
        assertEquals(BigDecimal.ZERO, breakdown.additionalCharges());
    }

    // ------------------------------------------------------------------------------------ rooms

    /** Confirms booked rooms expose their Room Type name and adult capacity (null capacity stays null). */
    @Test
    void shouldMapRoomTypeAndAdultCapacityOntoBookedRooms() {
        ReservationMapper mapper = new ReservationMapper();
        Reservation reservation = mock(Reservation.class, Answers.RETURNS_DEEP_STUBS);
        Guest guest = mock(Guest.class);
        when(guest.getId()).thenReturn(UUID.randomUUID());
        when(guest.getGuestCode()).thenReturn("G-1");
        when(reservation.getGuest()).thenReturn(guest);
        when(reservation.getStatus()).thenReturn(ReservationStatus.CONFIRMED);
        List<ReservationRoom> lines = List.of(bookedRoom("201", "Double Room", 2), bookedRoom("202", "Suite", null));
        when(reservation.getRooms()).thenReturn(lines);
        UUID updater = UUID.randomUUID();
        when(reservation.getUpdatedBy()).thenReturn(updater);

        ReservationDetailResponse detail =
                mapper.toDetailResponse(reservation, List.of(), null, null, null, false, "creator", "updater");

        assertEquals("Double Room", detail.rooms().get(0).roomTypeName());
        assertEquals(2, detail.rooms().get(0).adultCapacity());
        assertEquals("Suite", detail.rooms().get(1).roomTypeName());
        assertNull(detail.rooms().get(1).adultCapacity());
        assertEquals("updater", detail.updatedByUsername());
        assertEquals("creator", detail.createdByUsername());
    }

    /** Confirms current rooms and Room History carry Room Type, capacity and the change reason code. */
    @Test
    void shouldExposeRoomTypeCapacityAndReasonCodeFromAssignments() {
        StayRepository stays = mock(StayRepository.class);
        StayRoomAssignmentRepository assignments = mock(StayRoomAssignmentRepository.class);
        UUID stayId = UUID.randomUUID();
        Stay stay = mock(Stay.class);
        when(stay.getId()).thenReturn(stayId);
        when(stays.findByReservationId(RESERVATION_ID)).thenReturn(Optional.of(stay));
        StayRoomAssignment initial = assignment("201", "Double Room", 2, null, Instant.parse("2026-10-02T03:00:00Z"));
        StayRoomAssignment changed = assignment("305", "Suite", 3, RoomChangeReason.GUEST_REQUEST, null);
        List<StayRoomAssignment> open = List.of(changed);
        List<StayRoomAssignment> all = List.of(initial, changed);
        when(assignments.findOpenByStayId(stayId)).thenReturn(open);
        when(assignments.findByStayIdOrderByLineageAndTime(stayId)).thenReturn(all);
        StayRoomAssignmentQueryService service =
                new StayRoomAssignmentQueryService(stays, assignments, mock(AppUserRepository.class));

        CurrentRoomResponse current = service.findCurrentRooms(RESERVATION_ID).get(0);
        List<RoomHistoryLineResponse> history = service.findHistory(RESERVATION_ID);

        assertEquals("Suite", current.roomTypeName());
        assertEquals(3, current.adultCapacity());
        assertEquals("Double Room", history.get(0).roomTypeName());
        assertNull(history.get(0).reasonCode());
        assertEquals("Suite", history.get(1).roomTypeName());
        assertEquals("GUEST_REQUEST", history.get(1).reasonCode());
    }

    // ------------------------------------------------------------------------------------ display formats

    /** Confirms an Instant is split into the hotel-zone date and time used by the lifecycle subtitles. */
    @Test
    void shouldFormatInstantDateAndTimeInTheHotelZone() {
        Instant instant = Instant.parse("2026-10-06T02:42:00Z");

        assertEquals("06/10/2026", DisplayFormats.formatInstantDate(instant));
        assertEquals("09:42", DisplayFormats.formatInstantTime(instant));
        assertNull(DisplayFormats.formatInstantDate(null));
        assertNull(DisplayFormats.formatInstantTime(null));
    }

    private static ReservationRoom bookedRoom(String number, String typeName, Integer capacity) {
        ReservationRoom line = mock(ReservationRoom.class);
        Room room = room(number, typeName, capacity);
        when(line.getRoom()).thenReturn(room);
        when(line.getCheckInDate()).thenReturn(LocalDate.of(2026, 10, 5));
        when(line.getCheckOutDate()).thenReturn(LocalDate.of(2026, 10, 7));
        when(line.getNightlyRate()).thenReturn(new BigDecimal("1200000"));
        when(line.getTotalAmount()).thenReturn(new BigDecimal("2400000"));
        return line;
    }

    private static Room room(String number, String typeName, Integer capacity) {
        Room room = mock(Room.class);
        RoomType type = mock(RoomType.class);
        when(type.getName()).thenReturn(typeName);
        when(type.getCapacity()).thenReturn(capacity);
        when(room.getId()).thenReturn(UUID.randomUUID());
        when(room.getRoomNumber()).thenReturn(number);
        when(room.getRoomType()).thenReturn(type);
        return room;
    }

    private static StayRoomAssignment assignment(
            String number, String typeName, Integer capacity, RoomChangeReason reason, Instant to) {
        StayRoomAssignment assignment = mock(StayRoomAssignment.class);
        Room room = room(number, typeName, capacity);
        when(assignment.getId()).thenReturn(UUID.randomUUID());
        when(assignment.getRoom()).thenReturn(room);
        when(assignment.getAssignedFrom()).thenReturn(Instant.parse("2026-10-02T03:00:00Z"));
        when(assignment.getAssignedTo()).thenReturn(to);
        when(assignment.getReason()).thenReturn(reason);
        when(assignment.getCreatedBy()).thenReturn(UUID.randomUUID());
        return assignment;
    }
}
