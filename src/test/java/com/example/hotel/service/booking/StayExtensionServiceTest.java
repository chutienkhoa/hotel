package com.example.hotel.service.booking;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.example.hotel.dto.booking.request.StayExtensionRequest;
import com.example.hotel.dto.booking.response.StayExtensionPreviewResponse;
import com.example.hotel.dto.booking.response.StayExtensionPreviewResponse.State;
import com.example.hotel.dto.room.response.RoomImageFile;
import com.example.hotel.entity.booking.Charge;
import com.example.hotel.entity.booking.ChargeType;
import com.example.hotel.entity.booking.Reservation;
import com.example.hotel.entity.booking.ReservationRoom;
import com.example.hotel.entity.booking.ReservationStatus;
import com.example.hotel.entity.booking.Stay;
import com.example.hotel.entity.booking.StayExtension;
import com.example.hotel.entity.booking.StayExtensionRoom;
import com.example.hotel.entity.booking.StayRoomAssignment;
import com.example.hotel.entity.booking.StayStatus;
import com.example.hotel.entity.common.AuditLog;
import com.example.hotel.entity.customer.Guest;
import com.example.hotel.entity.room.Room;
import com.example.hotel.exception.StayExtensionException;
import com.example.hotel.exception.StayExtensionException.Reason;
import com.example.hotel.mapper.booking.ReservationMapper;
import com.example.hotel.repository.booking.ChargeRepository;
import com.example.hotel.repository.booking.ReservationRepository;
import com.example.hotel.repository.booking.StayExtensionRepository;
import com.example.hotel.repository.booking.StayExtensionRoomRepository;
import com.example.hotel.repository.booking.StayRepository;
import com.example.hotel.repository.booking.StayRoomAssignmentRepository;
import com.example.hotel.repository.common.AuditLogRepository;
import com.example.hotel.repository.room.RoomRepository;
import com.example.hotel.security.CurrentUser;
import com.example.hotel.service.room.RoomAvailabilityService;
import com.example.hotel.service.room.RoomImageService;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.mockito.ArgumentCaptor;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.http.HttpStatus;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.server.ResponseStatusException;

/** Verifies the Stay Extension rules with mocked persistence: state, dates, lineage rate, charges, audit, locking. */
class StayExtensionServiceTest {

    private static final ZoneId ZONE = ZoneId.of("Asia/Ho_Chi_Minh");
    private static final LocalDate CHECK_IN = LocalDate.of(2026, 9, 20);
    private static final LocalDate CHECK_OUT = LocalDate.of(2026, 9, 22);
    private static final BigDecimal RATE = new BigDecimal("1000000");

    private final ReservationRepository reservations = mock(ReservationRepository.class);
    private final StayRepository stays = mock(StayRepository.class);
    private final StayRoomAssignmentRepository assignments = mock(StayRoomAssignmentRepository.class);
    private final RoomRepository rooms = mock(RoomRepository.class);
    private final ChargeRepository charges = mock(ChargeRepository.class);
    private final StayExtensionRepository extensions = mock(StayExtensionRepository.class);
    private final StayExtensionRoomRepository extensionRooms = mock(StayExtensionRoomRepository.class);
    private final AuditLogRepository audits = mock(AuditLogRepository.class);
    private final RoomAvailabilityService availability = mock(RoomAvailabilityService.class);
    private final StayBalanceService balance = mock(StayBalanceService.class);
    private final RoomImageService roomImages = mock(RoomImageService.class);
    private final UUID actor = UUID.randomUUID();

    private Reservation reservation;
    private Stay stay;
    private final List<StayRoomAssignment> open = new ArrayList<>();
    private final List<ReservationRoom> lines = new ArrayList<>();

    @AfterEach
    void clear() {
        SecurityContextHolder.clearContext();
    }

    private StayExtensionService service(LocalDate today) {
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(new CurrentUser(actor, "manager"), null));
        return new StayExtensionService(reservations, stays, assignments, rooms, charges, extensions, extensionRooms,
                audits, new ReservationMapper(), availability, balance, roomImages,
                Clock.fixed(today.atTime(10, 0).atZone(ZONE).toInstant(), ZONE));
    }

    /** Builds a CHECKED_IN reservation with one line per given rate and stubs every repository. */
    private void checkedIn(String... rates) {
        Guest guest = Guest.create(UUID.randomUUID(), "G-1", "Ann", "Lee", null, null, null, null, null);
        reservation = new Reservation(UUID.randomUUID(), "R20260920-000001", guest, CHECK_IN, CHECK_OUT, "VND", null);
        for (int index = 0; index < rates.length; index++) {
            Room room = Room.create(UUID.randomUUID(), "20" + (index + 1), null, "2");
            ReservationRoom line = new ReservationRoom(reservation, room, CHECK_IN, CHECK_OUT, new BigDecimal(rates[index]));
            reservation.addRoom(line);
            lines.add(line);
        }
        reservation.calculateTotal();
        reservation.confirm();
        reservation.checkIn();
        stay = new Stay(reservation, Instant.parse("2026-09-20T07:00:00Z"));
        for (ReservationRoom line : lines) {
            open.add(new StayRoomAssignment(stay, line.getRoom(), line, stay.getActualCheckInAt(), null, null));
        }
        wire();
    }

    private void wire() {
        UUID id = reservation.getId();
        when(reservations.findById(id)).thenReturn(Optional.of(reservation));
        when(stays.findByReservationIdForUpdate(id)).thenReturn(Optional.of(stay));
        when(stays.findByReservationId(id)).thenReturn(Optional.of(stay));
        when(assignments.findOpenByStayIdWithLineage(stay.getId())).thenAnswer(invocation -> List.copyOf(open));
        when(rooms.lockAllByIdIn(anyList())).thenAnswer(invocation -> open.stream().map(StayRoomAssignment::getRoom).toList());
        when(availability.conflictedRoomIds(any(), any(), any(), any())).thenReturn(Set.of());
        when(extensions.findLastSequenceNo(stay.getId())).thenReturn(0);
    }

    private StayExtensionRequest request(LocalDate expected, LocalDate next) {
        return new StayExtensionRequest(expected, next);
    }

    private StayExtensionException rejected(StayExtensionService service, StayExtensionRequest request) {
        return assertThrows(StayExtensionException.class, () -> service.extend(reservation.getId(), request));
    }

    /** Confirms a CHECKED_IN stay extends: date moved, one line and one ROOM charge per lineage, one audit entry. */
    @Test
    void shouldExtendCheckedInStay() {
        checkedIn("1000000");
        var response = service(LocalDate.of(2026, 9, 21)).extend(reservation.getId(), request(CHECK_OUT, LocalDate.of(2026, 9, 24)));

        assertEquals("CHECKED_IN", response.status());
        assertEquals(LocalDate.of(2026, 9, 24), reservation.getCheckOutDate());
        ArgumentCaptor<StayExtension> event = ArgumentCaptor.forClass(StayExtension.class);
        verify(extensions).save(event.capture());
        assertEquals(1, event.getValue().getSequenceNo());
        assertEquals(CHECK_OUT, event.getValue().getPreviousCheckOutDate());
        ArgumentCaptor<AuditLog> audit = ArgumentCaptor.forClass(AuditLog.class);
        verify(audits).save(audit.capture());
        assertEquals("EXTEND_STAY", ReflectionTestUtils.getField(audit.getValue(), "action"));
        assertEquals("RESERVATION", ReflectionTestUtils.getField(audit.getValue(), "entityType"));
        assertTrue(String.valueOf(ReflectionTestUtils.getField(audit.getValue(), "newValue")).contains("total=2000000"));
    }

    /** Confirms every non-CHECKED_IN reservation state is rejected without any write. */
    @ParameterizedTest
    @EnumSource(value = ReservationStatus.class, names = {"CHECKED_IN"}, mode = EnumSource.Mode.EXCLUDE)
    void shouldRejectReservationsThatAreNotCheckedIn(ReservationStatus status) {
        checkedIn("1000000");
        ReflectionTestUtils.setField(reservation, "status", status);

        assertEquals(Reason.RESERVATION_NOT_CHECKED_IN,
                rejected(service(LocalDate.of(2026, 9, 21)), request(CHECK_OUT, LocalDate.of(2026, 9, 24))).getExtensionReason());
        verify(extensions, never()).save(any());
        verify(charges, never()).save(any());
        verify(audits, never()).save(any());
    }

    /** Confirms a missing Stay and an inconsistent Stay state are rejected and not repaired. */
    @Test
    void shouldRejectMissingAndInconsistentStay() {
        checkedIn("1000000");
        when(stays.findByReservationIdForUpdate(reservation.getId())).thenReturn(Optional.empty());
        assertEquals(Reason.STAY_NOT_FOUND,
                rejected(service(LocalDate.of(2026, 9, 21)), request(CHECK_OUT, LocalDate.of(2026, 9, 24))).getExtensionReason());

        when(stays.findByReservationIdForUpdate(reservation.getId())).thenReturn(Optional.of(stay));
        ReflectionTestUtils.setField(stay, "status", StayStatus.CHECKED_OUT);
        assertEquals(Reason.STAY_NOT_ACTIVE,
                rejected(service(LocalDate.of(2026, 9, 21)), request(CHECK_OUT, LocalDate.of(2026, 9, 24))).getExtensionReason());
        assertEquals(ReservationStatus.CHECKED_IN, reservation.getStatus());
        verify(extensions, never()).save(any());
    }

    private void assertRejected(StayExtensionService service, LocalDate expected, LocalDate next) {
        assertEquals(Reason.INVALID_NEW_CHECK_OUT_DATE, rejected(service, request(expected, next)).getExtensionReason(),
                "new " + next);
    }

    /** Confirms the rule new > current AND new >= today for an overdue stay (current 22, today 23). */
    @Test
    void shouldApplyTheOverdueDateRule() {
        checkedIn("1000000");
        StayExtensionService overdue = service(LocalDate.of(2026, 9, 23));

        assertRejected(overdue, CHECK_OUT, CHECK_OUT);
        assertRejected(overdue, CHECK_OUT, LocalDate.of(2026, 9, 21));
        assertEquals(CHECK_OUT, reservation.getCheckOutDate());

        overdue.extend(reservation.getId(), request(CHECK_OUT, LocalDate.of(2026, 9, 23)));
        assertEquals(LocalDate.of(2026, 9, 23), reservation.getCheckOutDate(), "an overdue stay may extend exactly to today");
    }

    /** Confirms an overdue extension to a later date is accepted too (22 to 24). */
    @Test
    void shouldAcceptAnOverdueExtensionBeyondToday() {
        checkedIn("1000000");
        service(LocalDate.of(2026, 9, 23)).extend(reservation.getId(), request(CHECK_OUT, LocalDate.of(2026, 9, 24)));
        assertEquals(LocalDate.of(2026, 9, 24), reservation.getCheckOutDate());
    }

    /** Confirms the rule when the current check-out is today: same day rejected, next day accepted. */
    @Test
    void shouldApplyTheRuleWhenCheckOutIsToday() {
        checkedIn("1000000");
        StayExtensionService sameDay = service(CHECK_OUT);

        assertRejected(sameDay, CHECK_OUT, CHECK_OUT);
        sameDay.extend(reservation.getId(), request(CHECK_OUT, CHECK_OUT.plusDays(1)));
        assertEquals(CHECK_OUT.plusDays(1), reservation.getCheckOutDate());
    }

    /** Confirms the rule when the check-out is in the future (current 25, today 23): only later dates pass. */
    @Test
    void shouldAlwaysMoveAFutureCheckOutForward() {
        checkedIn("1000000");
        StayExtensionService today = service(LocalDate.of(2026, 9, 21));
        today.extend(reservation.getId(), request(CHECK_OUT, LocalDate.of(2026, 9, 25)));
        StayExtensionService later = service(LocalDate.of(2026, 9, 23));

        assertRejected(later, LocalDate.of(2026, 9, 25), LocalDate.of(2026, 9, 24));
        assertRejected(later, LocalDate.of(2026, 9, 25), LocalDate.of(2026, 9, 25));
        later.extend(reservation.getId(), request(LocalDate.of(2026, 9, 25), LocalDate.of(2026, 9, 26)));
        assertEquals(LocalDate.of(2026, 9, 26), reservation.getCheckOutDate());
    }

    /** Confirms 22 to 23 bills exactly one night and 22 to 24 exactly two, both at the original lineage rate. */
    @Test
    void shouldBillExactlyTheAddedNightsAtTheOriginalRate() {
        checkedIn("1000000");
        service(LocalDate.of(2026, 9, 23)).extend(reservation.getId(), request(CHECK_OUT, LocalDate.of(2026, 9, 23)));
        ArgumentCaptor<Charge> one = ArgumentCaptor.forClass(Charge.class);
        verify(charges).save(one.capture());
        assertEquals(0, new BigDecimal("1").compareTo(one.getValue().getQuantity()));
        assertEquals(0, new BigDecimal("1000000").compareTo(one.getValue().getAmount()));
        assertEquals(0, RATE.compareTo(one.getValue().getUnitPrice()));
        verify(availability).conflictedRoomIds(anyList(), eq(CHECK_OUT), eq(LocalDate.of(2026, 9, 23)), eq(stay.getId()));
    }

    /** Confirms extension is valid the day before and on the planned check-out day. */
    @Test
    void shouldAllowBeforeAndSameDayExtension() {
        checkedIn("1000000");
        service(LocalDate.of(2026, 9, 21)).extend(reservation.getId(), request(CHECK_OUT, LocalDate.of(2026, 9, 23)));
        service(LocalDate.of(2026, 9, 23)).extend(reservation.getId(), request(LocalDate.of(2026, 9, 23), LocalDate.of(2026, 9, 24)));
        assertEquals(LocalDate.of(2026, 9, 24), reservation.getCheckOutDate());
    }

    /** Confirms an overdue extension bills from the old planned check-out, not from today. */
    @Test
    void shouldBillOverdueExtensionFromTheOldPlannedCheckOut() {
        checkedIn("1000000");
        service(LocalDate.of(2026, 9, 23)).extend(reservation.getId(), request(CHECK_OUT, LocalDate.of(2026, 9, 24)));

        ArgumentCaptor<Charge> charge = ArgumentCaptor.forClass(Charge.class);
        verify(charges).save(charge.capture());
        assertEquals(0, new BigDecimal("2000000").compareTo(charge.getValue().getAmount()), "22/09 and 23/09");
        assertEquals(0, new BigDecimal("2").compareTo(charge.getValue().getQuantity()));
        verify(availability).conflictedRoomIds(anyList(), eq(CHECK_OUT), eq(LocalDate.of(2026, 9, 24)), eq(stay.getId()));
    }

    /** Confirms the original booking snapshot and total are untouched. */
    @Test
    void shouldNotTouchTheOriginalBookingSnapshot() {
        checkedIn("1000000", "1500000");
        BigDecimal total = reservation.getTotalAmount();
        service(LocalDate.of(2026, 9, 21)).extend(reservation.getId(), request(CHECK_OUT, LocalDate.of(2026, 9, 25)));

        assertEquals(0, total.compareTo(reservation.getTotalAmount()));
        for (ReservationRoom line : lines) {
            assertEquals(CHECK_IN, line.getCheckInDate());
            assertEquals(CHECK_OUT, line.getCheckOutDate());
        }
        assertEquals(0, new BigDecimal("2000000").compareTo(lines.get(0).getTotalAmount()));
        assertEquals(0, new BigDecimal("3000000").compareTo(lines.get(1).getTotalAmount()));
    }

    /** Confirms a stale expected check-out is rejected and nothing is written. */
    @Test
    void shouldRejectStaleExpectedCheckOut() {
        checkedIn("1000000");
        assertEquals(Reason.STALE_CHECK_OUT_DATE, rejected(service(LocalDate.of(2026, 9, 21)),
                request(LocalDate.of(2026, 9, 21), LocalDate.of(2026, 9, 24))).getExtensionReason());
        verify(extensions, never()).save(any());
        verify(charges, never()).save(any());
    }

    /** Confirms a multi-room stay gets one line and one ROOM charge per lineage, each at its own original rate. */
    @Test
    void shouldCreateOneLineAndChargePerLineageAtItsOwnRate() {
        checkedIn("1000000", "1500000");
        service(LocalDate.of(2026, 9, 21)).extend(reservation.getId(), request(CHECK_OUT, LocalDate.of(2026, 9, 24)));

        ArgumentCaptor<StayExtensionRoom> saved = ArgumentCaptor.forClass(StayExtensionRoom.class);
        verify(extensionRooms, org.mockito.Mockito.times(2)).save(saved.capture());
        List<StayExtensionRoom> written = saved.getAllValues();
        assertEquals(lines.get(0), written.get(0).getOriginalReservationRoom());
        assertEquals(0, new BigDecimal("2000000").compareTo(written.get(0).getAmount()));
        assertEquals(lines.get(1), written.get(1).getOriginalReservationRoom());
        assertEquals(0, new BigDecimal("3000000").compareTo(written.get(1).getAmount()));
        assertEquals(CHECK_OUT, written.get(0).getFromDate());
        assertEquals(LocalDate.of(2026, 9, 24), written.get(0).getToDate());
        ArgumentCaptor<Charge> charge = ArgumentCaptor.forClass(Charge.class);
        verify(charges, org.mockito.Mockito.times(2)).save(charge.capture());
        assertTrue(charge.getAllValues().stream().allMatch(c -> c.getType() == ChargeType.ROOM));
        assertEquals("Room 201 extension 22/09/2026 - 24/09/2026", charge.getAllValues().get(0).getDescription());
        assertNotNull(written.get(1).getCharge());
    }

    /** Confirms after A to B the line keeps lineage A and the actual room B and the lineage rate ignores the room. */
    @Test
    void shouldStoreLineageAndActualRoomAfterARoomChange() {
        checkedIn("1000000");
        Room roomB = Room.create(UUID.randomUUID(), "305", null, "3");
        StayRoomAssignment onB = new StayRoomAssignment(stay, roomB, lines.get(0), Instant.parse("2026-09-21T03:00:00Z"), null, null);
        open.clear();
        open.add(onB);
        service(LocalDate.of(2026, 9, 21)).extend(reservation.getId(), request(CHECK_OUT, LocalDate.of(2026, 9, 24)));

        ArgumentCaptor<StayExtensionRoom> saved = ArgumentCaptor.forClass(StayExtensionRoom.class);
        verify(extensionRooms).save(saved.capture());
        assertEquals(lines.get(0), saved.getValue().getOriginalReservationRoom());
        assertEquals(roomB, saved.getValue().getRoom());
        assertEquals(0, RATE.compareTo(saved.getValue().getNightlyRate()));
        ArgumentCaptor<Charge> charge = ArgumentCaptor.forClass(Charge.class);
        verify(charges).save(charge.capture());
        assertEquals("Room 305 extension 22/09/2026 - 24/09/2026", charge.getValue().getDescription());
    }

    /** Confirms a conflicting external allocation rejects with no write, and no room is moved. */
    @Test
    void shouldRejectOnInventoryConflictWithoutAnyWrite() {
        checkedIn("1000000");
        when(availability.conflictedRoomIds(any(), any(), any(), any())).thenReturn(Set.of(lines.get(0).getRoom().getId()));

        StayExtensionException exception =
                rejected(service(LocalDate.of(2026, 9, 21)), request(CHECK_OUT, LocalDate.of(2026, 9, 24)));

        assertEquals(Reason.INVENTORY_CONFLICT, exception.getExtensionReason());
        assertEquals("201", exception.getArguments().get(0));
        assertEquals(CHECK_OUT, reservation.getCheckOutDate());
        verify(extensions, never()).save(any());
        verify(charges, never()).save(any());
        verify(audits, never()).save(any());
    }

    /** Confirms the lock order is Stay, then rooms, and that the current Stay is the one excluded from the check. */
    @Test
    void shouldLockStayThenRoomsThenCheckWithTheStayExcluded() {
        checkedIn("1000000");
        service(LocalDate.of(2026, 9, 21)).extend(reservation.getId(), request(CHECK_OUT, LocalDate.of(2026, 9, 24)));

        var order = inOrder(stays, rooms, availability);
        order.verify(stays).findByReservationIdForUpdate(reservation.getId());
        order.verify(rooms).lockAllByIdIn(anyList());
        order.verify(availability).conflictedRoomIds(anyList(), any(), any(), eq(stay.getId()));
    }

    /** Confirms a second extension continues the chain at the next sequence from the current planned check-out. */
    @Test
    void shouldContinueTheChainWithTheNextSequence() {
        checkedIn("1000000");
        service(LocalDate.of(2026, 9, 21)).extend(reservation.getId(), request(CHECK_OUT, LocalDate.of(2026, 9, 24)));
        when(extensions.findLastSequenceNo(stay.getId())).thenReturn(1);
        service(LocalDate.of(2026, 9, 21)).extend(reservation.getId(), request(LocalDate.of(2026, 9, 24), LocalDate.of(2026, 9, 25)));

        ArgumentCaptor<StayExtension> event = ArgumentCaptor.forClass(StayExtension.class);
        verify(extensions, org.mockito.Mockito.times(2)).save(event.capture());
        assertEquals(2, event.getAllValues().get(1).getSequenceNo());
        assertEquals(LocalDate.of(2026, 9, 24), event.getAllValues().get(1).getPreviousCheckOutDate());
        assertEquals(LocalDate.of(2026, 9, 25), event.getAllValues().get(1).getNewCheckOutDate());
    }

    /** Confirms extension is never gated by the folio: no balance is consulted. */
    @Test
    void shouldNotConsultTheBalance() {
        checkedIn("1000000");
        StayBalanceService balance = mock(StayBalanceService.class);
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(new CurrentUser(actor, "manager"), null));
        StayExtensionService gated = new StayExtensionService(reservations, stays, assignments, rooms, charges, extensions,
                extensionRooms, audits, new ReservationMapper(), availability, balance, mock(RoomImageService.class),
                Clock.fixed(LocalDate.of(2026, 9, 21).atTime(10, 0).atZone(ZONE).toInstant(), ZONE));

        gated.extend(reservation.getId(), request(CHECK_OUT, LocalDate.of(2026, 9, 24)));

        org.mockito.Mockito.verifyNoInteractions(balance);
    }

    /** Confirms the domain method never shortens or accepts a non-CHECKED_IN reservation. */
    @Test
    void shouldGuardTheDomainMethod() {
        checkedIn("1000000");
        assertThrows(IllegalStateException.class, () -> reservation.extendCheckOut(CHECK_OUT));
        assertThrows(IllegalStateException.class, () -> reservation.extendCheckOut(CHECK_OUT.minusDays(1)));
        ReflectionTestUtils.setField(reservation, "status", ReservationStatus.CONFIRMED);
        assertThrows(IllegalStateException.class, () -> reservation.extendCheckOut(CHECK_OUT.plusDays(1)));
    }

    /** Confirms the Select preview counts nights half-open and prices them at the lineage rate, with no folio by default. */
    @Test
    void shouldPreviewAnAvailableExtensionWithNightsAndTheOriginalRate() {
        checkedIn("1000000");

        StayExtensionPreviewResponse preview = service(LocalDate.of(2026, 9, 21))
                .preview(reservation.getId(), CHECK_OUT.plusDays(2), false);

        assertEquals(State.AVAILABLE, preview.state());
        assertTrue(preview.isAvailable());
        assertEquals(2, preview.currentNights());
        assertEquals(2, preview.additionalNights());
        assertEquals(4, preview.resultingNights());
        assertEquals(0, new BigDecimal("2000000").compareTo(preview.extensionAmount()));
        assertEquals(0, RATE.compareTo(preview.rooms().get(0).nightlyRate()));
        assertEquals(0, new BigDecimal("2000000").compareTo(preview.rooms().get(0).amount()));
        assertNull(preview.folio());
    }

    /** Confirms a date that is not after the current check-out, or is before today, is INVALID_DATE with no charge. */
    @Test
    void shouldPreviewAnInvalidDateWithoutAnyCharge() {
        checkedIn("1000000");

        StayExtensionPreviewResponse notAfter = service(LocalDate.of(2026, 9, 21))
                .preview(reservation.getId(), CHECK_OUT, false);
        StayExtensionPreviewResponse beforeToday = service(LocalDate.of(2026, 9, 25))
                .preview(reservation.getId(), LocalDate.of(2026, 9, 24), false);

        assertEquals(State.INVALID_DATE, notAfter.state());
        assertEquals(0, notAfter.additionalNights());
        assertNull(notAfter.extensionAmount());
        assertNull(notAfter.rooms().get(0).amount());
        assertEquals(State.INVALID_DATE, beforeToday.state());
        assertEquals(LocalDate.of(2026, 9, 25), beforeToday.earliestNewCheckOutDate());
    }

    /** Confirms no selected date is NOT_SELECTED, and never a priced or available state. */
    @Test
    void shouldPreviewNotSelectedWhenNoDateIsGiven() {
        checkedIn("1000000");

        StayExtensionPreviewResponse preview = service(LocalDate.of(2026, 9, 21)).preview(reservation.getId(), null, false);

        assertEquals(State.NOT_SELECTED, preview.state());
        assertFalse(preview.isAvailable());
        assertNull(preview.extensionAmount());
    }

    /** Confirms an inventory conflict is reported per room, while the amount stays informational and nothing is written. */
    @Test
    void shouldPreviewARoomConflictAsBlockedAndFlagTheRoom() {
        checkedIn("1000000");
        UUID roomId = open.get(0).getRoom().getId();
        when(availability.conflictedRoomIds(any(), eq(CHECK_OUT), eq(CHECK_OUT.plusDays(2)), eq(stay.getId())))
                .thenReturn(Set.of(roomId));

        StayExtensionPreviewResponse preview = service(LocalDate.of(2026, 9, 21))
                .preview(reservation.getId(), CHECK_OUT.plusDays(2), false);

        assertEquals(State.ROOM_CONFLICT, preview.state());
        assertFalse(preview.isAvailable());
        assertTrue(preview.rooms().get(0).conflicted());
        verify(extensions, never()).save(any());
        verify(charges, never()).save(any());
    }

    /** Confirms the Select screen shows the room that is current now, while the rate still comes from the lineage. */
    @Test
    void shouldPreviewTheCurrentRoomAfterARoomChangeAtTheLineageRate() {
        checkedIn("1000000");
        Room roomB = Room.create(UUID.randomUUID(), "305", null, "3");
        open.clear();
        open.add(new StayRoomAssignment(stay, roomB, lines.get(0), Instant.parse("2026-09-21T03:00:00Z"), null, null));

        StayExtensionPreviewResponse preview = service(LocalDate.of(2026, 9, 21))
                .preview(reservation.getId(), CHECK_OUT.plusDays(1), false);

        assertEquals("305", preview.rooms().get(0).roomNumber());
        assertEquals(roomB.getId(), preview.rooms().get(0).roomId());
        assertEquals(0, RATE.compareTo(preview.rooms().get(0).nightlyRate()));
    }

    /** Confirms the folio impact uses the StayBalance figures and adds the extension to the projected outstanding. */
    @Test
    void shouldIncludeTheFolioImpactFromTheStayBalanceWhenRequested() {
        checkedIn("1000000");
        when(balance.calculate(stay.getId())).thenReturn(new StayBalance(
                new BigDecimal("3000000"), new BigDecimal("500000"), new BigDecimal("2500000")));

        StayExtensionPreviewResponse preview = service(LocalDate.of(2026, 9, 21))
                .preview(reservation.getId(), CHECK_OUT.plusDays(2), true);

        assertEquals(0, new BigDecimal("2500000").compareTo(preview.folio().outstanding()));
        assertEquals(0, new BigDecimal("4500000").compareTo(preview.folio().projectedOutstanding()));
    }

    /** Confirms Review accepts only an available extension and, like Select, never writes. */
    @Test
    void shouldReviewAnAvailableExtensionWithoutWriting() {
        checkedIn("1000000");

        StayExtensionPreviewResponse review = service(LocalDate.of(2026, 9, 21))
                .review(reservation.getId(), request(CHECK_OUT, CHECK_OUT.plusDays(2)), false);

        assertTrue(review.isAvailable());
        assertEquals(CHECK_OUT, reservation.getCheckOutDate());
        verify(extensions, never()).save(any());
        verify(charges, never()).save(any());
        verify(audits, never()).save(any());
    }

    /** Confirms Review rejects a stale request, an invalid date, and a conflict with the same reasons as Confirm. */
    @Test
    void shouldReviewRejectStaleInvalidAndConflictedProposals() {
        checkedIn("1000000");
        StayExtensionService service = service(LocalDate.of(2026, 9, 21));

        StayExtensionException stale = assertThrows(StayExtensionException.class,
                () -> service.review(reservation.getId(), request(CHECK_OUT.minusDays(1), CHECK_OUT.plusDays(2)), false));
        StayExtensionException invalid = assertThrows(StayExtensionException.class,
                () -> service.review(reservation.getId(), request(CHECK_OUT, CHECK_OUT), false));
        when(availability.conflictedRoomIds(any(), any(), any(), any())).thenReturn(Set.of(open.get(0).getRoom().getId()));
        StayExtensionException conflict = assertThrows(StayExtensionException.class,
                () -> service.review(reservation.getId(), request(CHECK_OUT, CHECK_OUT.plusDays(2)), false));

        assertEquals(Reason.STALE_CHECK_OUT_DATE, stale.getExtensionReason());
        assertEquals(Reason.INVALID_NEW_CHECK_OUT_DATE, invalid.getExtensionReason());
        assertEquals(Reason.INVENTORY_CONFLICT, conflict.getExtensionReason());
        assertEquals("201", conflict.getArguments().get(0));
    }

    /** Confirms the current-room image is served only for a room currently assigned to the active Stay. */
    @Test
    void shouldServeTheImageOnlyForACurrentRoom() {
        checkedIn("1000000");
        UUID roomId = open.get(0).getRoom().getId();
        RoomImageFile image = new RoomImageFile(new ByteArrayResource("img".getBytes()), "image/jpeg", "room.jpg");
        when(roomImages.loadPrimaryImage(roomId)).thenReturn(Optional.of(image));
        StayExtensionService service = service(LocalDate.of(2026, 9, 21));

        assertSame(image, service.currentRoomImage(reservation.getId(), roomId));
        ResponseStatusException notCurrent = assertThrows(ResponseStatusException.class,
                () -> service.currentRoomImage(reservation.getId(), UUID.randomUUID()));
        assertEquals(HttpStatus.NOT_FOUND, notCurrent.getStatusCode());
    }

    /** Confirms the calendar's first unavailable checkout is the earliest date whose interval conflicts, found over the window. */
    @Test
    void shouldFindTheFirstUnavailableCheckOutInTheWindow() {
        checkedIn("1000000");
        UUID roomId = open.get(0).getRoom().getId();
        LocalDate firstConflict = CHECK_OUT.plusDays(4);
        when(availability.conflictedRoomIds(any(), any(), any(), any())).thenAnswer(invocation -> {
            LocalDate out = invocation.getArgument(2);
            return out.isBefore(firstConflict) ? Set.of() : Set.of(roomId);
        });

        StayExtensionPreviewResponse preview = service(LocalDate.of(2026, 9, 21))
                .preview(reservation.getId(), null, false);

        assertEquals(firstConflict, preview.firstUnavailableCheckOutDate());
        assertEquals(CHECK_OUT.plusDays(62), preview.availabilityKnownUntil());
    }

    /** Confirms no conflict in the window leaves the first unavailable date empty, so every window date is known free. */
    @Test
    void shouldReportNoUnavailableDateWhenTheWindowIsFree() {
        checkedIn("1000000");

        StayExtensionPreviewResponse preview = service(LocalDate.of(2026, 9, 21))
                .preview(reservation.getId(), null, false);

        assertNull(preview.firstUnavailableCheckOutDate());
        assertEquals(CHECK_OUT.plusDays(62), preview.availabilityKnownUntil());
    }
}
