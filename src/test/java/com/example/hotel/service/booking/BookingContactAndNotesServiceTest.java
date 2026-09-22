package com.example.hotel.service.booking;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.example.hotel.dto.booking.request.BookingContactUpdateRequest;
import com.example.hotel.dto.booking.request.CreateRequest;
import com.example.hotel.dto.booking.request.NotesUpdateRequest;
import com.example.hotel.dto.booking.request.RoomRequest;
import com.example.hotel.entity.booking.BookingSource;
import com.example.hotel.entity.booking.Reservation;
import com.example.hotel.entity.booking.ReservationStatus;
import com.example.hotel.entity.common.AuditLog;
import com.example.hotel.entity.customer.Guest;
import com.example.hotel.entity.room.Room;
import com.example.hotel.exception.ReservationFieldUpdateException;
import com.example.hotel.exception.ReservationFieldUpdateException.Reason;
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
import org.mockito.ArgumentCaptor;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.util.ReflectionTestUtils;

/**
 * Verifies Booking Contact defaulting/independence at creation and the two controlled field updates (Booking
 * Contact, Reservation Notes) that stay available through CHECKED_IN, including their privacy-safe audit trail.
 */
class BookingContactAndNotesServiceTest {

    private static final ZoneId ZONE = ZoneId.of("Asia/Ho_Chi_Minh");
    private static final LocalDate TODAY = LocalDate.of(2026, 9, 24);

    private final ReservationRepository reservations = mock(ReservationRepository.class);
    private final GuestRepository guests = mock(GuestRepository.class);
    private final RoomRepository rooms = mock(RoomRepository.class);
    private final StayRepository stays = mock(StayRepository.class);
    private final AuditLogRepository audits = mock(AuditLogRepository.class);
    private final ReservationNumberGenerator numberGenerator = mock(ReservationNumberGenerator.class);
    private final ReservationService service = new ReservationService(
            reservations,
            guests,
            rooms,
            stays,
            mock(StayRoomAssignmentRepository.class),
            mock(ChargeRepository.class),
            audits,
            new ReservationMapper(),
            numberGenerator,
            mock(StayBalanceService.class),
            mock(RoomAvailabilityService.class),
            mock(PrepaymentService.class),
            Clock.fixed(TODAY.atTime(10, 0).atZone(ZONE).toInstant(), ZONE));

    private final UUID actor = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(new CurrentUser(actor, "manager"), null, List.of()));
        when(numberGenerator.generate()).thenReturn("R20260924-000001");
    }

    @AfterEach
    void clearAuthentication() {
        SecurityContextHolder.clearContext();
    }

    /** Confirms a new Reservation defaults every blank Booking Contact field from the Primary Guest. */
    @Test
    void shouldDefaultBookingContactFromPrimaryGuestWhenNotSupplied() {
        Guest guest = guest("Ann", "Lee", "0900000001", "ann@example.test");
        UUID roomId = mockRoom();
        when(guests.findById(guest.getId())).thenReturn(Optional.of(guest));

        var response = service.create(createRequest(guest.getId(), roomId, null, null, null));

        Reservation saved = savedReservation();
        assertEquals(response.id(), saved.getId());
        assertEquals("Ann Lee", saved.getBookingContactName());
        assertEquals("0900000001", saved.getBookingContactPhone());
        assertEquals("ann@example.test", saved.getBookingContactEmail());
    }

    /** Confirms explicitly submitted Booking Contact values are preserved verbatim, not overwritten by the Guest. */
    @Test
    void shouldPreserveExplicitBookingContactValues() {
        Guest guest = guest("Ann", "Lee", "0900000001", "ann@example.test");
        UUID roomId = mockRoom();
        when(guests.findById(guest.getId())).thenReturn(Optional.of(guest));

        service.create(createRequest(guest.getId(), roomId, "Front Desk Agency", "0911111111", "agency@example.test"));

        Reservation saved = savedReservation();
        assertEquals("Front Desk Agency", saved.getBookingContactName());
        assertEquals("0911111111", saved.getBookingContactPhone());
        assertEquals("agency@example.test", saved.getBookingContactEmail());
    }

    /**
     * Confirms a partially explicit Booking Contact (name + email supplied, phone left blank) is stored exactly
     * as submitted, with the blank phone staying {@code null} rather than being individually backfilled from the
     * Primary Guest's phone.
     */
    @Test
    void shouldPreserveExplicitPartialBookingContactWithoutMixingPrimaryGuestPhone() {
        Guest guest = guest("Ann", "Lee", "0900000001", "ann@example.test");
        UUID roomId = mockRoom();
        when(guests.findById(guest.getId())).thenReturn(Optional.of(guest));

        service.create(createRequest(guest.getId(), roomId, "A", null, "a@example.com"));

        Reservation saved = savedReservation();
        assertEquals("A", saved.getBookingContactName());
        assertNull(saved.getBookingContactPhone());
        assertEquals("a@example.com", saved.getBookingContactEmail());
    }

    /**
     * Confirms an explicit Booking Contact supplying only a phone number is stored exactly as submitted, with the
     * blank name and email staying {@code null} rather than being individually backfilled from the Primary Guest.
     */
    @Test
    void shouldPreserveExplicitPhoneOnlyBookingContactWithoutMixingPrimaryGuestNameOrEmail() {
        Guest guest = guest("Ann", "Lee", "0900000001", "ann@example.test");
        UUID roomId = mockRoom();
        when(guests.findById(guest.getId())).thenReturn(Optional.of(guest));

        service.create(createRequest(guest.getId(), roomId, null, "0933333333", null));

        Reservation saved = savedReservation();
        assertNull(saved.getBookingContactName());
        assertEquals("0933333333", saved.getBookingContactPhone());
        assertNull(saved.getBookingContactEmail());
    }

    /** Confirms a later Guest profile change never mutates an already-created Booking Contact snapshot. */
    @Test
    void shouldNotMutateBookingContactWhenGuestProfileChangesLater() {
        Guest guest = guest("Ann", "Lee", "0900000001", "ann@example.test");
        UUID roomId = mockRoom();
        when(guests.findById(guest.getId())).thenReturn(Optional.of(guest));
        service.create(createRequest(guest.getId(), roomId, null, null, null));
        Reservation saved = savedReservation();

        guest.updateProfile("Ann", "Lee", "changed@example.test", "0999999999", null, null, null);

        assertEquals("0900000001", saved.getBookingContactPhone());
        assertEquals("ann@example.test", saved.getBookingContactEmail());
    }

    /** Confirms updating the Booking Contact never touches the Guest entity. */
    @Test
    void shouldNotMutateGuestWhenBookingContactIsUpdated() {
        Guest guest = guest("Ann", "Lee", "0900000001", "ann@example.test");
        Reservation reservation = confirmedFixture(guest);
        when(reservations.findByIdForUpdate(reservation.getId())).thenReturn(Optional.of(reservation));

        service.updateBookingContact(reservation.getId(),
                new BookingContactUpdateRequest("Someone Else", "0922222222", "else@example.test"));

        assertEquals("Ann", guest.getFirstName());
        assertEquals("0900000001", guest.getPhone());
        assertEquals("ann@example.test", guest.getEmail());
    }

    /** Confirms Booking Contact updates succeed in DRAFT, CONFIRMED and CHECKED_IN. */
    @ParameterizedTest
    @EnumSource(value = ReservationStatus.class, names = {"DRAFT", "CONFIRMED", "CHECKED_IN"})
    void shouldAllowBookingContactUpdateWhileEditable(ReservationStatus status) {
        Reservation reservation = confirmedFixture(guest("Ann", "Lee", "0900000001", "ann@example.test"));
        ReflectionTestUtils.setField(reservation, "status", status);
        when(reservations.findByIdForUpdate(reservation.getId())).thenReturn(Optional.of(reservation));

        service.updateBookingContact(reservation.getId(),
                new BookingContactUpdateRequest("New Contact", "0933333333", "new@example.test"));

        assertEquals("New Contact", reservation.getBookingContactName());
        assertEquals("0933333333", reservation.getBookingContactPhone());
        assertEquals("new@example.test", reservation.getBookingContactEmail());
    }

    /** Confirms Booking Contact updates are rejected once CHECKED_OUT, CANCELLED or NO_SHOW. */
    @ParameterizedTest
    @EnumSource(value = ReservationStatus.class, names = {"CHECKED_OUT", "CANCELLED", "NO_SHOW"})
    void shouldRejectBookingContactUpdateOnceLocked(ReservationStatus status) {
        Reservation reservation = confirmedFixture(guest("Ann", "Lee", "0900000001", "ann@example.test"));
        ReflectionTestUtils.setField(reservation, "status", status);
        when(reservations.findByIdForUpdate(reservation.getId())).thenReturn(Optional.of(reservation));

        ReservationFieldUpdateException exception = assertThrows(ReservationFieldUpdateException.class,
                () -> service.updateBookingContact(reservation.getId(),
                        new BookingContactUpdateRequest("New Contact", null, null)));

        assertEquals(Reason.RESERVATION_LOCKED, exception.getFieldUpdateReason());
        assertEquals("Ann Lee", reservation.getBookingContactName());
        verify(audits, never()).save(any());
    }

    /** Confirms a successful Booking Contact update writes exactly one audit entry naming only the changed fields. */
    @Test
    void shouldAuditBookingContactUpdateWithoutRawValues() {
        Reservation reservation = confirmedFixture(guest("Ann", "Lee", "0900000001", "ann@example.test"));
        when(reservations.findByIdForUpdate(reservation.getId())).thenReturn(Optional.of(reservation));

        service.updateBookingContact(reservation.getId(),
                new BookingContactUpdateRequest("Ann Lee", "0977777777", "ann@example.test"));

        ArgumentCaptor<AuditLog> captor = ArgumentCaptor.forClass(AuditLog.class);
        verify(audits, times(1)).save(captor.capture());
        AuditLog audit = captor.getValue();
        assertEquals(ReservationService.UPDATE_BOOKING_CONTACT_AUDIT_ACTION, field(audit, "action"));
        assertNull(field(audit, "oldValue"));
        String newValue = (String) field(audit, "newValue");
        assertEquals("changedFields=phone", newValue);
        assertFalse(newValue.contains("0977777777"));
        assertFalse(newValue.contains("Ann Lee"));
        assertFalse(newValue.contains("ann@example.test"));
    }

    /** Confirms Reservation Notes updates succeed in DRAFT, CONFIRMED and CHECKED_IN. */
    @ParameterizedTest
    @EnumSource(value = ReservationStatus.class, names = {"DRAFT", "CONFIRMED", "CHECKED_IN"})
    void shouldAllowNotesUpdateWhileEditable(ReservationStatus status) {
        Reservation reservation = confirmedFixture(guest("Ann", "Lee", "0900000001", "ann@example.test"));
        ReflectionTestUtils.setField(reservation, "status", status);
        when(reservations.findByIdForUpdate(reservation.getId())).thenReturn(Optional.of(reservation));

        service.updateReservationNotes(reservation.getId(), new NotesUpdateRequest("Guest called ahead"));

        assertEquals("Guest called ahead", reservation.getNotes());
    }

    /** Confirms Reservation Notes updates are rejected once CHECKED_OUT, CANCELLED or NO_SHOW. */
    @ParameterizedTest
    @EnumSource(value = ReservationStatus.class, names = {"CHECKED_OUT", "CANCELLED", "NO_SHOW"})
    void shouldRejectNotesUpdateOnceLocked(ReservationStatus status) {
        Reservation reservation = confirmedFixture(guest("Ann", "Lee", "0900000001", "ann@example.test"));
        ReflectionTestUtils.setField(reservation, "status", status);
        when(reservations.findByIdForUpdate(reservation.getId())).thenReturn(Optional.of(reservation));

        ReservationFieldUpdateException exception = assertThrows(ReservationFieldUpdateException.class,
                () -> service.updateReservationNotes(reservation.getId(), new NotesUpdateRequest("late arrival")));

        assertEquals(Reason.RESERVATION_LOCKED, exception.getFieldUpdateReason());
        verify(audits, never()).save(any());
    }

    /** Confirms a successful Notes update writes exactly one audit entry with no notes content at all. */
    @Test
    void shouldAuditNotesUpdateWithoutRawContent() {
        Reservation reservation = confirmedFixture(guest("Ann", "Lee", "0900000001", "ann@example.test"));
        when(reservations.findByIdForUpdate(reservation.getId())).thenReturn(Optional.of(reservation));

        service.updateReservationNotes(reservation.getId(),
                new NotesUpdateRequest("Guest requested late checkout, called at 9am"));

        ArgumentCaptor<AuditLog> captor = ArgumentCaptor.forClass(AuditLog.class);
        verify(audits, times(1)).save(captor.capture());
        AuditLog audit = captor.getValue();
        assertEquals(ReservationService.UPDATE_NOTES_AUDIT_ACTION, field(audit, "action"));
        assertNull(field(audit, "oldValue"));
        assertNull(field(audit, "newValue"));
    }

    /** Confirms the Primary Guest fallback is used only when every Booking Contact field is blank. */
    @Test
    void shouldResolveEffectiveContactFromPrimaryGuestOnlyWhenSnapshotIsBlank() {
        Guest guest = guest("Ann", "Lee", "0900000001", "ann@example.test");
        Reservation withSnapshot = confirmedFixture(guest);
        withSnapshot.changeBookingContact("Explicit Contact", "0955555555", "explicit@example.test");
        EffectiveBookingContact withSnapshotResult = EffectiveBookingContact.of(withSnapshot);
        assertFalse(withSnapshotResult.fromPrimaryGuest());
        assertEquals("Explicit Contact", withSnapshotResult.name());

        Reservation withoutSnapshot = confirmedFixture(guest);
        withoutSnapshot.changeBookingContact(null, null, null);
        EffectiveBookingContact fallback = EffectiveBookingContact.of(withoutSnapshot);
        assertTrue(fallback.fromPrimaryGuest());
        assertEquals("Ann Lee", fallback.name());
        assertEquals("0900000001", fallback.phone());
        assertEquals("ann@example.test", fallback.email());
    }

    /**
     * Confirms a Reservation with a PARTIAL stored Booking Contact snapshot (one field present) is returned
     * as-is: the present field verbatim, the blank sibling fields as {@code null}, never individually filled from
     * the Primary Guest even though the Guest has both a phone and an email.
     */
    @Test
    void shouldReturnPartialStoredSnapshotAsIsWithoutFillingSiblingFieldsFromPrimaryGuest() {
        Guest guest = guest("Ann", "Lee", "0900000001", "ann@example.test");
        Reservation reservation = confirmedFixture(guest);
        reservation.changeBookingContact("A", null, "a@example.com");

        EffectiveBookingContact result = EffectiveBookingContact.of(reservation);

        assertFalse(result.fromPrimaryGuest());
        assertEquals("A", result.name());
        assertNull(result.phone());
        assertEquals("a@example.com", result.email());
    }

    private Guest guest(String firstName, String lastName, String phone, String email) {
        return Guest.create(UUID.randomUUID(), "G" + UUID.randomUUID().toString().substring(0, 8),
                firstName, lastName, email, phone, "VN", null, null);
    }

    private UUID mockRoom() {
        UUID roomId = UUID.randomUUID();
        Room room = Room.create(roomId, "101", null, "1");
        when(rooms.findAllById(any())).thenReturn(List.of(room));
        return roomId;
    }

    private CreateRequest createRequest(UUID guestId, UUID roomId, String name, String phone, String email) {
        return new CreateRequest(guestId, TODAY, TODAY.plusDays(2), 1, 0, BookingSource.DIRECT, null, "VND", null,
                List.of(new RoomRequest(roomId, new BigDecimal("1000000"))), null, name, phone, email);
    }

    /** Builds a CONFIRMED, saved-shaped Reservation fixture with a defaulted Booking Contact. */
    private Reservation confirmedFixture(Guest guest) {
        Reservation reservation = new Reservation(
                UUID.randomUUID(), "R-1", guest, TODAY, TODAY.plusDays(2), 1, 0, BookingSource.DIRECT, null, "VND",
                null);
        reservation.changeBookingContact("Ann Lee", "0900000001", "ann@example.test");
        reservation.confirm();
        return reservation;
    }

    private Reservation savedReservation() {
        ArgumentCaptor<Reservation> captor = ArgumentCaptor.forClass(Reservation.class);
        verify(reservations, times(1)).save(captor.capture());
        return captor.getValue();
    }

    private Object field(AuditLog audit, String name) {
        return ReflectionTestUtils.getField(audit, name);
    }
}
