package com.example.hotel.service.booking;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.RETURNS_DEEP_STUBS;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.example.hotel.dto.booking.request.CreateRequest;
import com.example.hotel.dto.booking.request.RoomRequest;
import com.example.hotel.dto.booking.request.WalkInRequest;
import com.example.hotel.dto.booking.response.CheckInReviewResponse;
import com.example.hotel.dto.booking.response.CheckInTiming;
import com.example.hotel.dto.booking.response.Response;
import com.example.hotel.dto.booking.response.WalkInReviewResponse;
import com.example.hotel.dto.customer.response.GuestLookupResponse;
import com.example.hotel.dto.room.response.RoomLookupResponse;
import com.example.hotel.entity.booking.BookingSource;
import com.example.hotel.entity.booking.Reservation;
import com.example.hotel.entity.booking.ReservationRoom;
import com.example.hotel.entity.booking.ReservationStatus;
import com.example.hotel.entity.customer.Guest;
import com.example.hotel.entity.room.Room;
import com.example.hotel.entity.room.RoomStatus;
import com.example.hotel.entity.room.RoomType;
import com.example.hotel.mapper.customer.GuestMapper;
import com.example.hotel.repository.booking.ReservationRepository;
import com.example.hotel.repository.customer.GuestRepository;
import com.example.hotel.repository.room.RoomRepository;
import com.example.hotel.service.customer.GuestDocumentService;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.web.server.ResponseStatusException;

/** Verifies the Check-in orchestration flows without duplicating the Reservation lifecycle tests. */
class CheckInServiceTest {

    private static final ZoneId ZONE = ZoneId.of("Asia/Ho_Chi_Minh");

    /** Confirms review() classifies EARLY, NORMAL, and LATE against the scheduled check-in date. */
    @Test
    void shouldClassifyEarlyNormalAndLateCheckInTiming() {
        LocalDate scheduled = LocalDate.of(2026, 9, 15);
        assertEquals(CheckInTiming.EARLY, review(scheduled, scheduled.minusDays(1)).timing());
        assertEquals(CheckInTiming.NORMAL, review(scheduled, scheduled).timing());
        assertEquals(CheckInTiming.LATE, review(scheduled, scheduled.plusDays(1)).timing());
    }

    /** Confirms EARLY is never eligible, even though the Reservation is CONFIRMED. */
    @Test
    void shouldMarkEarlyAsNotEligibleForCheckIn() {
        LocalDate scheduled = LocalDate.of(2026, 9, 15);
        CheckInReviewResponse response = review(scheduled, scheduled.minusDays(1));
        assertTrue(!response.eligibleForCheckIn());
    }

    /** Confirms a non-CONFIRMED Reservation is never eligible regardless of date. */
    @Test
    void shouldMarkNonConfirmedAsNotEligibleForCheckIn() {
        Fixture fixture = fixture(Clock.fixed(
                LocalDate.of(2026, 9, 15).atTime(10, 0).atZone(ZONE).toInstant(), ZONE));
        Reservation reservation = reservationWithRoom(LocalDate.of(2026, 9, 15), false);
        when(fixture.reservationRepository.findById(reservation.getId())).thenReturn(Optional.of(reservation));
        when(fixture.guestDocumentService.hasPassport(any())).thenReturn(false);

        CheckInReviewResponse response = fixture.service.review(reservation.getId());

        assertEquals("DRAFT", response.status());
        assertTrue(!response.eligibleForCheckIn());
    }

    /** Confirms availableRoomsForRange excludes OUT_OF_ORDER rooms and rooms with an overlapping stay. */
    @Test
    void shouldExcludeOutOfOrderAndOverlappingRoomsFromAvailability() {
        Fixture fixture = fixture(Clock.systemDefaultZone());
        Room available = activeRoom(RoomStatus.AVAILABLE);
        Room occupiedOverlap = activeRoom(RoomStatus.AVAILABLE);
        Room outOfOrder = activeRoom(RoomStatus.OUT_OF_ORDER);
        when(fixture.roomRepository.findByActiveTrue()).thenReturn(List.of(available, occupiedOverlap, outOfOrder));
        LocalDate checkIn = LocalDate.of(2026, 9, 20);
        LocalDate checkOut = LocalDate.of(2026, 9, 22);
        when(fixture.roomRepository.findRoomIdsWithInventoryConflict(
                        any(), eq(checkIn), eq(checkOut), any(), any(), anyBoolean(), any(), any(), any()))
                .thenReturn(List.of(occupiedOverlap.getId()));

        List<RoomLookupResponse> result = fixture.service.availableRoomsForRange(checkIn, checkOut);

        assertEquals(1, result.size());
        assertEquals(available.getId(), result.get(0).id());
    }

    /**
     * Confirms the Walk-in list (today to the requested check-out) offers only check-in-ready Rooms: DIRTY,
     * CLEANING, MAINTENANCE, OUT_OF_ORDER and OCCUPIED Rooms are not presented, while an AVAILABLE Room with no
     * conflict remains eligible. This matches the room-status validation enforced by check-in.
     */
    @Test
    void shouldOfferOnlyCheckInReadyRoomsToWalkIn() {
        Fixture fixture = fixture(Clock.fixed(
                LocalDate.of(2026, 9, 20).atTime(10, 0).atZone(ZONE).toInstant(), ZONE));
        Room ready = activeRoom(RoomStatus.AVAILABLE);
        when(fixture.roomRepository.findByActiveTrue()).thenReturn(List.of(
                activeRoom(RoomStatus.DIRTY),
                activeRoom(RoomStatus.CLEANING),
                activeRoom(RoomStatus.MAINTENANCE),
                activeRoom(RoomStatus.OUT_OF_ORDER),
                activeRoom(RoomStatus.OCCUPIED),
                ready));

        List<RoomLookupResponse> result = fixture.service.availableRoomsForWalkIn(LocalDate.of(2026, 9, 22));

        assertEquals(1, result.size());
        assertEquals(ready.getId(), result.get(0).id());
        assertEquals("AVAILABLE", result.get(0).status());
    }

    /** Confirms the existing check-in room-status validation is untouched: a non-AVAILABLE room still cannot be checked in. */
    @Test
    void shouldStillRejectCheckInOfNonAvailableRoomInTheLifecycle() {
        Room dirty = activeRoom(RoomStatus.DIRTY);

        assertTrue(!dirty.isReadyForCheckIn());
        assertThrows(IllegalStateException.class, dirty::occupy);
    }

    /** Confirms OTA Booking Not Entered rejects DIRECT as a source. */
    @Test
    void shouldRejectDirectSourceForOtaEntry() {
        Fixture fixture = fixture(Clock.systemDefaultZone());
        CreateRequest request = new CreateRequest(
                UUID.randomUUID(), LocalDate.now(), LocalDate.now().plusDays(1), 1, 0, BookingSource.DIRECT, null, "VND", null,
                List.of(new RoomRequest(UUID.randomUUID(), BigDecimal.TEN)), List.of());

        assertThrows(ResponseStatusException.class, () -> fixture.service.createOtaEntry(request));
    }

    /** Confirms OTA Booking Not Entered creates then confirms the Reservation, reusing existing lifecycle. */
    @Test
    void shouldCreateAndConfirmOtaEntry() {
        Fixture fixture = fixture(Clock.systemDefaultZone());
        UUID reservationId = UUID.randomUUID();
        CreateRequest request = new CreateRequest(
                UUID.randomUUID(), LocalDate.now(), LocalDate.now().plusDays(1), 2, 1, BookingSource.AGODA, "AG-1", "VND", null,
                List.of(new RoomRequest(UUID.randomUUID(), BigDecimal.TEN)), List.of());
        when(fixture.reservationService.create(request))
                .thenReturn(new Response(reservationId, "R1", "DRAFT", BigDecimal.TEN, "VND"));
        when(fixture.reservationService.confirm(reservationId))
                .thenReturn(new Response(reservationId, "R1", "CONFIRMED", BigDecimal.TEN, "VND"));

        Response result = fixture.service.createOtaEntry(request);

        assertEquals("CONFIRMED", result.status());
        var order = inOrder(fixture.reservationService);
        order.verify(fixture.reservationService).create(request);
        order.verify(fixture.reservationService).confirm(reservationId);
    }

    /** Confirms Walk-in always uses DIRECT and the hotel current date from the Clock, never client input. */
    @Test
    void shouldConfirmWalkInAsDirectUsingHotelClockDate() {
        LocalDate today = LocalDate.of(2026, 9, 16);
        Fixture fixture = fixture(Clock.fixed(today.atTime(9, 0).atZone(ZONE).toInstant(), ZONE));
        UUID reservationId = UUID.randomUUID();
        WalkInRequest request = new WalkInRequest(
                UUID.randomUUID(), today.plusDays(2), 3, 1, "VND", null, List.of(new RoomRequest(UUID.randomUUID(), BigDecimal.TEN)));
        when(fixture.reservationService.create(any(CreateRequest.class)))
                .thenReturn(new Response(reservationId, "R1", "DRAFT", BigDecimal.TEN, "VND"));
        when(fixture.reservationService.confirm(reservationId))
                .thenReturn(new Response(reservationId, "R1", "CONFIRMED", BigDecimal.TEN, "VND"));
        when(fixture.reservationService.checkIn(reservationId))
                .thenReturn(new Response(reservationId, "R1", "CHECKED_IN", BigDecimal.TEN, "VND"));

        fixture.service.confirmWalkIn(request);

        ArgumentCaptor<CreateRequest> captor = ArgumentCaptor.forClass(CreateRequest.class);
        verify(fixture.reservationService).create(captor.capture());
        assertEquals(BookingSource.DIRECT, captor.getValue().source());
        assertEquals(today, captor.getValue().checkInDate());
        assertEquals(3, captor.getValue().adultCount());
        assertEquals(1, captor.getValue().childCount());
        var order = inOrder(fixture.reservationService);
        order.verify(fixture.reservationService).create(any(CreateRequest.class));
        order.verify(fixture.reservationService).confirm(reservationId);
        order.verify(fixture.reservationService).checkIn(reservationId);
    }

    /** Confirms a Walk-in confirm() failure stops the orchestration before check-in is ever attempted. */
    @Test
    void shouldNotCheckInWhenWalkInConfirmFails() {
        Fixture fixture = fixture(Clock.systemDefaultZone());
        UUID reservationId = UUID.randomUUID();
        WalkInRequest request = new WalkInRequest(
                UUID.randomUUID(), LocalDate.now().plusDays(1), 1, 0, "VND", null,
                List.of(new RoomRequest(UUID.randomUUID(), BigDecimal.TEN)));
        when(fixture.reservationService.create(any(CreateRequest.class)))
                .thenReturn(new Response(reservationId, "R1", "DRAFT", BigDecimal.TEN, "VND"));
        when(fixture.reservationService.confirm(reservationId))
                .thenThrow(new ResponseStatusException(org.springframework.http.HttpStatus.CONFLICT, "Room is already booked for these dates"));

        assertThrows(ResponseStatusException.class, () -> fixture.service.confirmWalkIn(request));

        verify(fixture.reservationService, never()).checkIn(any());
    }

    /** Confirms Walk-in review computes the total from the submitted rooms without persisting anything. */
    @Test
    void shouldComputeWalkInReviewTotalWithoutPersisting() {
        Fixture fixture = fixture(Clock.systemDefaultZone());
        Guest guest = mock(Guest.class, RETURNS_DEEP_STUBS);
        UUID guestId = UUID.randomUUID();
        when(guest.getId()).thenReturn(guestId);
        when(guest.getGuestCode()).thenReturn("GUEST-001");
        when(fixture.guestRepository.findById(guestId)).thenReturn(Optional.of(guest));
        when(fixture.guestMapper.toLookupResponse(guest))
                .thenReturn(new GuestLookupResponse(guestId, "GUEST-001", "Nguyen Van A", null, null, null));
        when(fixture.guestDocumentService.findPassports(guestId)).thenReturn(List.of());
        Room room = activeRoom(RoomStatus.AVAILABLE);
        when(fixture.roomRepository.findById(room.getId())).thenReturn(Optional.of(room));
        LocalDate checkOut = LocalDate.now().plusDays(2);
        WalkInRequest request = new WalkInRequest(
                guestId, checkOut, 1, 0, "VND", null, List.of(new RoomRequest(room.getId(), new BigDecimal("1000000"))));

        WalkInReviewResponse review = fixture.service.reviewWalkIn(request);

        assertEquals(0, new BigDecimal("2000000").compareTo(review.totalAmount()));
        verify(fixture.reservationService, never()).create(any());
        verify(fixture.reservationService, never()).confirm(any());
        verify(fixture.reservationService, never()).checkIn(any());
    }

    /** Confirms the review exposes a NEEDS_ATTENTION readiness (and is not eligible) for a DIRTY assigned room. */
    @Test
    void shouldExposeReadinessBlockerForDirtyRoomInReview() {
        LocalDate today = LocalDate.of(2026, 9, 15);
        Fixture fixture = fixture(Clock.fixed(today.atTime(10, 0).atZone(ZONE).toInstant(), ZONE));
        Reservation reservation = reservationWithRoom(today, true);
        org.springframework.test.util.ReflectionTestUtils.setField(
                reservation.getRooms().get(0).getRoom(), "status", RoomStatus.DIRTY);
        when(fixture.reservationRepository.findById(reservation.getId())).thenReturn(Optional.of(reservation));
        when(fixture.guestDocumentService.hasPassport(any())).thenReturn(true);

        CheckInReviewResponse response = fixture.service.review(reservation.getId());

        assertEquals(com.example.hotel.dto.booking.response.ArrivalReadinessState.NEEDS_ATTENTION,
                response.readiness().state());
        assertEquals(com.example.hotel.dto.booking.response.ArrivalIssueCode.ROOM_DIRTY,
                response.readiness().blockers().get(0).code());
        assertTrue(!response.eligibleForCheckIn());
    }

    /** Confirms a missing passport does not block: the review stays eligible and shows only a warning. */
    @Test
    void shouldKeepReviewEligibleWhenPassportIsMissing() {
        CheckInReviewResponse response = review(LocalDate.of(2026, 9, 15), LocalDate.of(2026, 9, 15));

        assertTrue(response.eligibleForCheckIn());
        assertEquals(com.example.hotel.dto.booking.response.ArrivalReadinessState.READY, response.readiness().state());
        assertTrue(response.readiness().issues().stream().anyMatch(issue ->
                issue.code() == com.example.hotel.dto.booking.response.ArrivalIssueCode.PASSPORT_MISSING));
    }

    /** Confirms a past-due CONFIRMED reservation stays visible as an overdue warning, still CONFIRMED and eligible. */
    @Test
    void shouldShowPastDueArrivalAsWarningUsingTheHotelClock() {
        CheckInReviewResponse response = review(LocalDate.of(2026, 9, 15), LocalDate.of(2026, 9, 18));

        assertEquals("CONFIRMED", response.status());
        assertEquals(CheckInTiming.LATE, response.readiness().timing());
        assertTrue(response.eligibleForCheckIn());
        assertTrue(response.readiness().issues().stream().anyMatch(issue ->
                issue.code() == com.example.hotel.dto.booking.response.ArrivalIssueCode.ARRIVAL_OVERDUE));
    }

    /**
     * Confirms Accompanying Guests are read-only information on the review: they are loaded with ONE call (no query per
     * Guest), and their presence or missing passports never changes readiness or eligibility.
     */
    @Test
    void shouldExposeAccompanyingGuestsWithoutChangingReadinessOrEligibility() {
        LocalDate today = LocalDate.of(2026, 9, 15);
        Fixture fixture = fixture(Clock.fixed(today.atTime(10, 0).atZone(ZONE).toInstant(), ZONE));
        Reservation reservation = reservationWithRoom(today, true);
        when(fixture.reservationRepository.findById(reservation.getId())).thenReturn(Optional.of(reservation));
        when(fixture.guestDocumentService.hasPassport(any())).thenReturn(true);
        CheckInReviewResponse without = fixture.service.review(reservation.getId());

        when(fixture.reservationQueryService.findAccompanyingGuests(reservation.getId())).thenReturn(List.of(
                new com.example.hotel.dto.booking.response.AccompanyingGuestResponse(UUID.randomUUID(), "G-2"),
                new com.example.hotel.dto.booking.response.AccompanyingGuestResponse(UUID.randomUUID(), "G-3")));
        CheckInReviewResponse with = fixture.service.review(reservation.getId());

        assertEquals(2, with.accompanyingGuests().size());
        assertEquals(without.readiness(), with.readiness());
        assertEquals(without.eligibleForCheckIn(), with.eligibleForCheckIn());
        assertEquals(1, with.adultCount());
        assertEquals(0, with.childCount());
        verify(fixture.guestDocumentService, org.mockito.Mockito.times(2)).hasPassport(any());
        verify(fixture.reservationQueryService, org.mockito.Mockito.times(2)).findAccompanyingGuests(reservation.getId());
    }

    /** Builds a Check-in Review for a Reservation with the supplied scheduled/current dates. */
    private CheckInReviewResponse review(LocalDate scheduledCheckIn, LocalDate today) {
        Fixture fixture = fixture(Clock.fixed(today.atTime(10, 0).atZone(ZONE).toInstant(), ZONE));
        Reservation reservation = reservationWithRoom(scheduledCheckIn, true);
        when(fixture.reservationRepository.findById(reservation.getId())).thenReturn(Optional.of(reservation));
        when(fixture.guestDocumentService.hasPassport(any())).thenReturn(false);
        return fixture.service.review(reservation.getId());
    }

    /** Creates a Reservation with one assigned Room, optionally CONFIRMED. */
    private Reservation reservationWithRoom(LocalDate checkInDate, boolean confirmed) {
        Guest guest = Guest.create(
                UUID.randomUUID(), "GUEST-001", "First", "Last", null, null, "Vietnam", null, null);
        Room room = activeRoom(RoomStatus.AVAILABLE);
        Reservation reservation = new Reservation(
                UUID.randomUUID(), "R20260915-000001", guest, checkInDate, checkInDate.plusDays(2), "VND", null);
        reservation.addRoom(new ReservationRoom(
                reservation, room, checkInDate, checkInDate.plusDays(2), new BigDecimal("1000000")));
        reservation.calculateTotal();
        if (confirmed) {
            reservation.confirm();
        }
        return reservation;
    }

    /** Creates an active mocked Room with the supplied status. */
    private Room activeRoom(RoomStatus status) {
        RoomType type = mock(RoomType.class, invocation -> "getCapacity".equals(invocation.getMethod().getName()) ? Integer.valueOf(2) : null);
        Room room = Room.create(UUID.randomUUID(), "101", type, "1");
        org.springframework.test.util.ReflectionTestUtils.setField(room, "status", status);
        return room;
    }

    /** Wires a CheckInService with fully mocked collaborators for one test. */
    private Fixture fixture(Clock clock) {
        ReservationRepository reservationRepository = mock(ReservationRepository.class);
        ReservationQueryService reservationQueryService = mock(ReservationQueryService.class);
        ReservationService reservationService = mock(ReservationService.class);
        RoomRepository roomRepository = mock(RoomRepository.class);
        com.example.hotel.repository.booking.StayRepository stayRepository =
                mock(com.example.hotel.repository.booking.StayRepository.class);
        GuestRepository guestRepository = mock(GuestRepository.class);
        GuestMapper guestMapper = mock(GuestMapper.class);
        GuestDocumentService guestDocumentService = mock(GuestDocumentService.class);
        when(guestMapper.toLookupResponse(any())).thenAnswer(invocation -> {
            Guest guest = invocation.getArgument(0);
            return new GuestLookupResponse(guest.getId(), guest.getGuestCode(), "First Last", null, null, null);
        });

        CheckInService service = new CheckInService(
                reservationRepository,
                reservationQueryService,
                reservationService,
                roomRepository,
                new com.example.hotel.service.room.RoomAvailabilityService(roomRepository, clock),
                stayRepository,
                guestRepository,
                guestMapper,
                guestDocumentService,
                clock);

        return new Fixture(
                service,
                reservationQueryService,
                reservationRepository,
                reservationService,
                roomRepository,
                guestRepository,
                guestMapper,
                guestDocumentService);
    }

    /** Bundles a wired CheckInService with its mocked collaborators. */
    private record Fixture(
            CheckInService service,
            ReservationQueryService reservationQueryService,
            ReservationRepository reservationRepository,
            ReservationService reservationService,
            RoomRepository roomRepository,
            GuestRepository guestRepository,
            GuestMapper guestMapper,
            GuestDocumentService guestDocumentService) {}
}
