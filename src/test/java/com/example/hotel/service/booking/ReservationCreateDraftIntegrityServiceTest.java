package com.example.hotel.service.booking;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.example.hotel.dto.booking.request.CreateRequest;
import com.example.hotel.dto.booking.request.RoomRequest;
import com.example.hotel.entity.booking.BookingSource;
import com.example.hotel.entity.booking.Reservation;
import com.example.hotel.entity.booking.ReservationStatus;
import com.example.hotel.entity.customer.Guest;
import com.example.hotel.entity.room.Room;
import com.example.hotel.entity.room.RoomType;
import com.example.hotel.exception.LocalizedResponseStatusException;
import com.example.hotel.mapper.booking.ReservationMapper;
import com.example.hotel.repository.booking.ChargeRepository;
import com.example.hotel.repository.booking.ReservationRepository;
import com.example.hotel.repository.booking.StayRepository;
import com.example.hotel.repository.booking.StayRoomAssignmentRepository;
import com.example.hotel.repository.common.AuditLogRepository;
import com.example.hotel.repository.customer.GuestRepository;
import com.example.hotel.repository.room.RoomRepository;
import com.example.hotel.security.CurrentUser;
import com.example.hotel.service.room.RoomAvailabilityService;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.http.HttpStatus;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.util.ReflectionTestUtils;

/**
 * Verifies the Create Reservation (Save as Draft) contract at the service boundary: Rooms must be bookable inventory,
 * but a DRAFT is never blocked by booking overlap or adult capacity (both stay Confirm rules), and every create-time
 * rejection carries a message key so the page can localize it and place it on its field.
 */
class ReservationCreateDraftIntegrityServiceTest {

    private static final LocalDate CHECK_IN = LocalDate.of(2026, 10, 10);
    private static final LocalDate CHECK_OUT = LocalDate.of(2026, 10, 12);
    private static final ZoneId ZONE = ZoneId.of("Asia/Ho_Chi_Minh");

    private final ReservationRepository reservations = mock(ReservationRepository.class);
    private final GuestRepository guests = mock(GuestRepository.class);
    private final RoomRepository rooms = mock(RoomRepository.class);
    private final RoomAvailabilityService availability = mock(RoomAvailabilityService.class);
    private final ReservationNumberGenerator numbers = mock(ReservationNumberGenerator.class);
    private final ReservationService service = new ReservationService(
            reservations,
            guests,
            rooms,
            mock(StayRepository.class),
            mock(StayRoomAssignmentRepository.class),
            mock(ChargeRepository.class),
            mock(AuditLogRepository.class),
            new ReservationMapper(),
            numbers,
            mock(StayBalanceService.class),
            availability,
            mock(PrepaymentService.class),
            Clock.fixed(CHECK_IN.atTime(10, 0).atZone(ZONE).toInstant(), ZONE));

    private final Guest guest =
            Guest.create(UUID.randomUUID(), "G-1", "Ann", "Lee", null, null, "Vietnam", null, null);

    /** Authenticates and stubs the lookups every creation needs. */
    @BeforeEach
    void setUp() {
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(
                new CurrentUser(UUID.randomUUID(), "manager"), null, List.of()));
        when(guests.findById(guest.getId())).thenReturn(Optional.of(guest));
        when(numbers.generate()).thenReturn("R20261010-000001");
        when(reservations.save(any(Reservation.class))).thenAnswer(invocation -> invocation.getArgument(0));
    }

    @AfterEach
    void clearContext() {
        SecurityContextHolder.clearContext();
    }

    /** Confirms a Room that is inactive cannot be attached to a new DRAFT, even by a crafted request. */
    @Test
    void shouldRejectInactiveRoomOnCreate() {
        Room room = room("101", 2);
        ReflectionTestUtils.setField(room, "active", false);

        assertNotBookable(room);
    }

    /** Confirms MAINTENANCE and OUT_OF_ORDER Rooms cannot be attached to a new DRAFT. */
    @Test
    void shouldRejectMaintenanceAndOutOfOrderRoomsOnCreate() {
        Room maintenance = room("102", 2);
        maintenance.startMaintenance();
        Room outOfOrder = room("103", 2);
        outOfOrder.markOutOfOrder();

        assertNotBookable(maintenance);
        assertNotBookable(outOfOrder);
    }

    /** Confirms a Room that is OCCUPIED, DIRTY or CLEANING today is still bookable inventory for a DRAFT. */
    @Test
    void shouldAcceptOccupiedDirtyAndCleaningRoomsOnCreate() {
        Room occupied = room("201", 2);
        occupied.occupy();
        Room dirty = room("202", 2);
        dirty.occupy();
        dirty.markDirty();
        Room cleaning = room("203", 2);
        cleaning.occupy();
        cleaning.markDirty();
        cleaning.startCleaning();
        when(rooms.findAllById(any())).thenReturn(List.of(occupied, dirty, cleaning));

        assertDoesNotThrow(() -> service.create(request(9,
                new RoomRequest(occupied.getId(), new BigDecimal("1000000")),
                new RoomRequest(dirty.getId(), new BigDecimal("1000000")),
                new RoomRequest(cleaning.getId(), new BigDecimal("1000000")))));
    }

    /**
     * Confirms Save as Draft is not a replacement for Confirm: an overlapping booking and adults above the rooms'
     * capacity are both accepted at create time, and neither availability nor capacity is even consulted.
     */
    @Test
    void shouldCreateDraftEvenWhenRoomsOverlapAndAdultCapacityIsExceeded() {
        Room room = room("301", 1);
        when(rooms.findAllById(any())).thenReturn(List.of(room));
        when(availability.conflictedRoomIds(any(), any(), any())).thenReturn(java.util.Set.of(room.getId()));

        var response = service.create(request(5, new RoomRequest(room.getId(), new BigDecimal("1500000"))));

        assertEquals(ReservationStatus.DRAFT.name(), response.status());
        verifyNoInteractions(availability);
    }

    /** Confirms editing a DRAFT still keeps a Room already assigned even if it has since gone out of service. */
    @Test
    void shouldNotApplyTheCreateIntegrityCheckWhenEditingADraft() {
        Room room = room("401", 2);
        room.markOutOfOrder();
        when(rooms.findAllById(any())).thenReturn(List.of(room));
        Reservation draft = new Reservation(
                UUID.randomUUID(), "R20261010-000002", guest, CHECK_IN, CHECK_OUT, "VND", null);
        when(reservations.findById(draft.getId())).thenReturn(Optional.of(draft));

        assertDoesNotThrow(() -> service.updateDraft(draft.getId(),
                request(2, new RoomRequest(room.getId(), new BigDecimal("1000000")))));
    }

    /** Confirms a fractional-dong rate names the room it belongs to, so the page can mark that row. */
    @Test
    void shouldReportTheRoomOfAFractionalNightlyRate() {
        Room room = room("501", 2);
        when(rooms.findAllById(any())).thenReturn(List.of(room));

        LocalizedResponseStatusException exception = assertThrows(LocalizedResponseStatusException.class,
                () -> service.create(request(2, new RoomRequest(room.getId(), new BigDecimal("1000.5")))));

        assertEquals("reservation.create.error.nightlyRateScale", exception.getMessageKey());
        assertArrayEquals(new Object[] {"501", room.getId()}, exception.getMessageArguments());
        assertEquals("Rate exceeds currency precision", exception.getReason());
        verify(reservations, never()).save(any(Reservation.class));
    }

    /** Confirms create-time rejections carry a message key (and keep their English reason) for the page. */
    @Test
    void shouldRaiseLocalizableErrorsForCreateTimeStructuralRules() {
        Room room = room("601", 2);
        when(rooms.findAllById(any())).thenReturn(List.of(room));
        RoomRequest line = new RoomRequest(room.getId(), new BigDecimal("1000000"));

        assertKey("reservation.create.error.checkOutAfterCheckIn", HttpStatus.BAD_REQUEST,
                () -> service.create(new CreateRequest(guest.getId(), CHECK_OUT, CHECK_IN, 2, 0,
                        BookingSource.DIRECT, null, "VND", null, List.of(line), List.of())));
        assertKey("reservation.create.error.duplicateRoom", HttpStatus.BAD_REQUEST,
                () -> service.create(request(2, line, line)));
        assertKey("reservation.create.error.accompanyingIsPrimary", HttpStatus.BAD_REQUEST,
                () -> service.create(new CreateRequest(guest.getId(), CHECK_IN, CHECK_OUT, 2, 0,
                        BookingSource.DIRECT, null, "VND", null, List.of(line), List.of(guest.getId()))));
        UUID missingGuest = UUID.randomUUID();
        assertKey("reservation.create.error.guestNotFound", HttpStatus.NOT_FOUND,
                () -> service.create(new CreateRequest(missingGuest, CHECK_IN, CHECK_OUT, 2, 0,
                        BookingSource.DIRECT, null, "VND", null, List.of(line), List.of())));
        when(rooms.findAllById(any())).thenReturn(List.of());
        assertKey("reservation.create.error.roomNotFound", HttpStatus.NOT_FOUND, () -> service.create(request(2, line)));
    }

    /** Confirms a non-DIRECT source without a reference is refused with its localized key before anything is saved. */
    @Test
    void shouldRequireAnOtaReferenceForNonDirectSources() {
        Room room = room("701", 2);
        when(rooms.findAllById(any())).thenReturn(List.of(room));

        assertKey("reservation.ota.error.referenceRequired", HttpStatus.BAD_REQUEST,
                () -> service.create(new CreateRequest(guest.getId(), CHECK_IN, CHECK_OUT, 2, 0,
                        BookingSource.AGODA, " ", "VND", null,
                        List.of(new RoomRequest(room.getId(), new BigDecimal("1000000"))), List.of())));
    }

    /** Confirms every booking source is accepted by Create when its OTA rule is met. */
    @ParameterizedTest
    @EnumSource(BookingSource.class)
    void shouldAcceptEveryBookingSourceWhenItsReferenceRuleIsMet(BookingSource source) {
        Room room = room("801", 2);
        when(rooms.findAllById(any())).thenReturn(List.of(room));
        when(reservations.existsByOtaIdentity(any(), any(), any())).thenReturn(false);
        String reference = source == BookingSource.DIRECT ? null : "REF-1";

        assertDoesNotThrow(() -> service.create(new CreateRequest(guest.getId(), CHECK_IN, CHECK_OUT, 2, 0, source,
                reference, "VND", null, List.of(new RoomRequest(room.getId(), new BigDecimal("1000000"))), List.of())));
    }

    /** Confirms a duplicate OTA identity is refused with its localized key and nothing is saved. */
    @Test
    void shouldRejectADuplicateOtaIdentity() {
        Room room = room("901", 2);
        when(rooms.findAllById(any())).thenReturn(List.of(room));
        when(reservations.existsByOtaIdentity(any(), any(), any())).thenReturn(true);

        assertKey("reservation.ota.error.duplicateIdentity", HttpStatus.CONFLICT,
                () -> service.create(new CreateRequest(guest.getId(), CHECK_IN, CHECK_OUT, 2, 0,
                        BookingSource.AIRBNB, "DUP-1", "VND", null,
                        List.of(new RoomRequest(room.getId(), new BigDecimal("1000000"))), List.of())));
        verify(reservations, never()).save(any(Reservation.class));
    }

    private void assertNotBookable(Room room) {
        when(rooms.findAllById(any())).thenReturn(List.of(room));

        LocalizedResponseStatusException exception = assertThrows(LocalizedResponseStatusException.class,
                () -> service.create(request(2, new RoomRequest(room.getId(), new BigDecimal("1000000")))));

        assertEquals(HttpStatus.BAD_REQUEST, exception.getStatusCode());
        assertEquals("reservation.create.error.roomNotBookable", exception.getMessageKey());
        assertArrayEquals(new Object[] {room.getRoomNumber(), room.getId()}, exception.getMessageArguments());
        verify(reservations, never()).save(any(Reservation.class));
    }

    private void assertKey(String key, HttpStatus status, org.junit.jupiter.api.function.Executable action) {
        LocalizedResponseStatusException exception = assertThrows(LocalizedResponseStatusException.class, action);

        assertEquals(key, exception.getMessageKey());
        assertEquals(status, exception.getStatusCode());
    }

    private static Room room(String number, int capacity) {
        RoomType type = mock(RoomType.class);
        when(type.getCapacity()).thenReturn(capacity);
        return Room.create(UUID.randomUUID(), number, type, "1");
    }

    private CreateRequest request(int adults, RoomRequest... lines) {
        return new CreateRequest(guest.getId(), CHECK_IN, CHECK_OUT, adults, 0, BookingSource.DIRECT, null, "VND", null,
                List.of(lines), List.of());
    }
}
