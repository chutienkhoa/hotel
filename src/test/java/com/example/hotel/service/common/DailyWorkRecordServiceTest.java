package com.example.hotel.service.common;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.example.hotel.dto.common.request.DailyWorkRecordLineForm;
import com.example.hotel.entity.common.DailyWorkRecord;
import com.example.hotel.entity.common.Staff;
import com.example.hotel.repository.common.DailyWorkRecordRepository;
import com.example.hotel.repository.common.StaffRepository;
import com.example.hotel.security.CurrentUser;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.server.ResponseStatusException;

/** Verifies the Daily Work Record bulk entry load/validate/save behavior. */
class DailyWorkRecordServiceTest {

    private static final LocalDate WORK_DATE = LocalDate.of(2026, 9, 18);

    /** Clears the authenticated user established by each test. */
    @AfterEach
    void clearSecurityContext() {
        SecurityContextHolder.clearContext();
    }

    /** Confirms loading lines returns exactly one line per active Staff member, in Staff Code order. */
    @Test
    void shouldLoadOneLinePerActiveStaffMember() {
        StaffRepository staffRepository = mock(StaffRepository.class);
        DailyWorkRecordRepository recordRepository = mock(DailyWorkRecordRepository.class);
        Staff staffA = staff("STF-000001", "Nguyen", "Van A");
        Staff staffB = staff("STF-000002", "Tran", "Thi B");
        when(staffRepository.findByActiveTrueOrderByStaffCodeAsc()).thenReturn(List.of(staffA, staffB));
        when(recordRepository.findByWorkDate(WORK_DATE)).thenReturn(List.of());

        List<DailyWorkRecordLineForm> lines = service(staffRepository, recordRepository).loadLines(WORK_DATE);

        assertEquals(2, lines.size());
        assertEquals("STF-000001", lines.get(0).getStaffCode());
        assertEquals("STF-000002", lines.get(1).getStaffCode());
        assertNull(lines.get(0).getStartTime());
    }

    /** Confirms an existing record's values prefill its line, including the derived Working Time. */
    @Test
    void shouldPrefillLineFromExistingRecordWithWorkingTime() {
        StaffRepository staffRepository = mock(StaffRepository.class);
        DailyWorkRecordRepository recordRepository = mock(DailyWorkRecordRepository.class);
        Staff staffA = staff("STF-000001", "Nguyen", "Van A");
        when(staffRepository.findByActiveTrueOrderByStaffCodeAsc()).thenReturn(List.of(staffA));
        DailyWorkRecord existing =
                DailyWorkRecord.create(UUID.randomUUID(), staffA, WORK_DATE, LocalTime.of(8, 0), LocalTime.of(17, 0), "Note");
        when(recordRepository.findByWorkDate(WORK_DATE)).thenReturn(List.of(existing));

        List<DailyWorkRecordLineForm> lines = service(staffRepository, recordRepository).loadLines(WORK_DATE);

        assertEquals(LocalTime.of(8, 0), lines.get(0).getStartTime());
        assertEquals(LocalTime.of(17, 0), lines.get(0).getEndTime());
        assertEquals("Note", lines.get(0).getNotes());
        assertEquals("9h00", lines.get(0).getWorkingTime());
    }

    /** Confirms 08:15 to 17:10 derives the expected 8h55 Working Time. */
    @Test
    void shouldDeriveWorkingTimeWithMinutePrecision() {
        StaffRepository staffRepository = mock(StaffRepository.class);
        DailyWorkRecordRepository recordRepository = mock(DailyWorkRecordRepository.class);
        Staff staffA = staff("STF-000001", "Nguyen", "Van A");
        when(staffRepository.findByActiveTrueOrderByStaffCodeAsc()).thenReturn(List.of(staffA));
        DailyWorkRecord existing = DailyWorkRecord.create(
                UUID.randomUUID(), staffA, WORK_DATE, LocalTime.of(8, 15), LocalTime.of(17, 10), null);
        when(recordRepository.findByWorkDate(WORK_DATE)).thenReturn(List.of(existing));

        List<DailyWorkRecordLineForm> lines = service(staffRepository, recordRepository).loadLines(WORK_DATE);

        assertEquals("8h55", lines.get(0).getWorkingTime());
    }

    /** Confirms a new complete line is created for a Staff/date with no existing record. */
    @Test
    void shouldCreateNewRecordForCompleteLine() {
        StaffRepository staffRepository = mock(StaffRepository.class);
        DailyWorkRecordRepository recordRepository = mock(DailyWorkRecordRepository.class);
        Staff staffA = staff("STF-000001", "Nguyen", "Van A");
        when(staffRepository.findByActiveTrueOrderByStaffCodeAsc()).thenReturn(List.of(staffA));
        when(recordRepository.findByStaffIdAndWorkDate(staffA.getId(), WORK_DATE)).thenReturn(Optional.empty());
        setCurrentUser(UUID.randomUUID());

        service(staffRepository, recordRepository)
                .saveBulk(WORK_DATE, List.of(line(staffA, LocalTime.of(8, 0), LocalTime.of(17, 0), null)));

        verify(recordRepository).save(any(DailyWorkRecord.class));
    }

    /** Confirms changing times for an existing record updates it in place rather than creating a second row. */
    @Test
    void shouldUpdateExistingRecordRatherThanCreateSecondRow() {
        StaffRepository staffRepository = mock(StaffRepository.class);
        DailyWorkRecordRepository recordRepository = mock(DailyWorkRecordRepository.class);
        Staff staffA = staff("STF-000001", "Nguyen", "Van A");
        when(staffRepository.findByActiveTrueOrderByStaffCodeAsc()).thenReturn(List.of(staffA));
        DailyWorkRecord existing = DailyWorkRecord.create(
                UUID.randomUUID(), staffA, WORK_DATE, LocalTime.of(8, 0), LocalTime.of(17, 0), null);
        when(recordRepository.findByStaffIdAndWorkDate(staffA.getId(), WORK_DATE)).thenReturn(Optional.of(existing));
        setCurrentUser(UUID.randomUUID());

        service(staffRepository, recordRepository)
                .saveBulk(WORK_DATE, List.of(line(staffA, LocalTime.of(8, 30), LocalTime.of(17, 30), null)));

        assertEquals(LocalTime.of(8, 30), existing.getStartTime());
        assertEquals(LocalTime.of(17, 30), existing.getEndTime());
        verify(recordRepository, times(1)).save(existing);
        verify(recordRepository, never()).delete(any(DailyWorkRecord.class));
    }

    /** Confirms clearing both fields on an existing record removes it. */
    @Test
    void shouldRemoveExistingRecordWhenBothFieldsCleared() {
        StaffRepository staffRepository = mock(StaffRepository.class);
        DailyWorkRecordRepository recordRepository = mock(DailyWorkRecordRepository.class);
        Staff staffA = staff("STF-000001", "Nguyen", "Van A");
        when(staffRepository.findByActiveTrueOrderByStaffCodeAsc()).thenReturn(List.of(staffA));
        DailyWorkRecord existing = DailyWorkRecord.create(
                UUID.randomUUID(), staffA, WORK_DATE, LocalTime.of(8, 0), LocalTime.of(17, 0), null);
        when(recordRepository.findByStaffIdAndWorkDate(staffA.getId(), WORK_DATE)).thenReturn(Optional.of(existing));
        setCurrentUser(UUID.randomUUID());

        service(staffRepository, recordRepository).saveBulk(WORK_DATE, List.of(line(staffA, null, null, null)));

        verify(recordRepository).delete(existing);
        verify(recordRepository, never()).save(any(DailyWorkRecord.class));
    }

    /** Confirms both fields blank for a Staff/date with no existing record creates nothing. */
    @Test
    void shouldCreateNothingWhenBothFieldsBlankAndNoExistingRecord() {
        StaffRepository staffRepository = mock(StaffRepository.class);
        DailyWorkRecordRepository recordRepository = mock(DailyWorkRecordRepository.class);
        Staff staffA = staff("STF-000001", "Nguyen", "Van A");
        when(staffRepository.findByActiveTrueOrderByStaffCodeAsc()).thenReturn(List.of(staffA));
        when(recordRepository.findByStaffIdAndWorkDate(staffA.getId(), WORK_DATE)).thenReturn(Optional.empty());
        setCurrentUser(UUID.randomUUID());

        service(staffRepository, recordRepository).saveBulk(WORK_DATE, List.of(line(staffA, null, null, null)));

        verify(recordRepository, never()).save(any(DailyWorkRecord.class));
        verify(recordRepository, never()).delete(any(DailyWorkRecord.class));
    }

    /** Confirms only Start populated is rejected as invalid. */
    @Test
    void shouldRejectOnlyStartPopulated() {
        StaffRepository staffRepository = mock(StaffRepository.class);
        DailyWorkRecordRepository recordRepository = mock(DailyWorkRecordRepository.class);
        Staff staffA = staff("STF-000001", "Nguyen", "Van A");
        when(staffRepository.findByActiveTrueOrderByStaffCodeAsc()).thenReturn(List.of(staffA));
        setCurrentUser(UUID.randomUUID());

        ResponseStatusException exception = assertThrows(ResponseStatusException.class, () -> service(
                        staffRepository, recordRepository)
                .saveBulk(WORK_DATE, List.of(line(staffA, LocalTime.of(8, 0), null, null))));

        assertEquals(400, exception.getStatusCode().value());
        verify(recordRepository, never()).save(any(DailyWorkRecord.class));
    }

    /** Confirms only End populated is rejected as invalid. */
    @Test
    void shouldRejectOnlyEndPopulated() {
        StaffRepository staffRepository = mock(StaffRepository.class);
        DailyWorkRecordRepository recordRepository = mock(DailyWorkRecordRepository.class);
        Staff staffA = staff("STF-000001", "Nguyen", "Van A");
        when(staffRepository.findByActiveTrueOrderByStaffCodeAsc()).thenReturn(List.of(staffA));
        setCurrentUser(UUID.randomUUID());

        ResponseStatusException exception = assertThrows(ResponseStatusException.class, () -> service(
                        staffRepository, recordRepository)
                .saveBulk(WORK_DATE, List.of(line(staffA, null, LocalTime.of(17, 0), null))));

        assertEquals(400, exception.getStatusCode().value());
    }

    /** Confirms Start equal to End is rejected: V1 requires strictly Start before End. */
    @Test
    void shouldRejectStartEqualToEnd() {
        StaffRepository staffRepository = mock(StaffRepository.class);
        DailyWorkRecordRepository recordRepository = mock(DailyWorkRecordRepository.class);
        Staff staffA = staff("STF-000001", "Nguyen", "Van A");
        when(staffRepository.findByActiveTrueOrderByStaffCodeAsc()).thenReturn(List.of(staffA));
        setCurrentUser(UUID.randomUUID());

        assertThrows(ResponseStatusException.class, () -> service(staffRepository, recordRepository)
                .saveBulk(WORK_DATE, List.of(line(staffA, LocalTime.of(8, 0), LocalTime.of(8, 0), null))));
    }

    /** Confirms Start after End is rejected. */
    @Test
    void shouldRejectStartAfterEnd() {
        StaffRepository staffRepository = mock(StaffRepository.class);
        DailyWorkRecordRepository recordRepository = mock(DailyWorkRecordRepository.class);
        Staff staffA = staff("STF-000001", "Nguyen", "Van A");
        when(staffRepository.findByActiveTrueOrderByStaffCodeAsc()).thenReturn(List.of(staffA));
        setCurrentUser(UUID.randomUUID());

        assertThrows(ResponseStatusException.class, () -> service(staffRepository, recordRepository)
                .saveBulk(WORK_DATE, List.of(line(staffA, LocalTime.of(17, 0), LocalTime.of(8, 0), null))));
    }

    /** Confirms Start strictly before End is valid and persists. */
    @Test
    void shouldAcceptStartBeforeEnd() {
        StaffRepository staffRepository = mock(StaffRepository.class);
        DailyWorkRecordRepository recordRepository = mock(DailyWorkRecordRepository.class);
        Staff staffA = staff("STF-000001", "Nguyen", "Van A");
        when(staffRepository.findByActiveTrueOrderByStaffCodeAsc()).thenReturn(List.of(staffA));
        when(recordRepository.findByStaffIdAndWorkDate(staffA.getId(), WORK_DATE)).thenReturn(Optional.empty());
        setCurrentUser(UUID.randomUUID());

        service(staffRepository, recordRepository)
                .saveBulk(WORK_DATE, List.of(line(staffA, LocalTime.of(9, 0), LocalTime.of(18, 15), null)));

        verify(recordRepository).save(any(DailyWorkRecord.class));
    }

    /** Confirms multiple Staff members can be saved together in one bulk operation. */
    @Test
    void shouldSaveMultipleStaffLinesInOneBulkOperation() {
        StaffRepository staffRepository = mock(StaffRepository.class);
        DailyWorkRecordRepository recordRepository = mock(DailyWorkRecordRepository.class);
        Staff staffA = staff("STF-000001", "Nguyen", "Van A");
        Staff staffB = staff("STF-000002", "Tran", "Thi B");
        when(staffRepository.findByActiveTrueOrderByStaffCodeAsc()).thenReturn(List.of(staffA, staffB));
        when(recordRepository.findByStaffIdAndWorkDate(any(UUID.class), org.mockito.ArgumentMatchers.eq(WORK_DATE)))
                .thenReturn(Optional.empty());
        setCurrentUser(UUID.randomUUID());

        service(staffRepository, recordRepository).saveBulk(
                WORK_DATE,
                List.of(
                        line(staffA, LocalTime.of(8, 0), LocalTime.of(17, 0), null),
                        line(staffB, LocalTime.of(7, 30), LocalTime.of(16, 30), null)));

        verify(recordRepository, times(2)).save(any(DailyWorkRecord.class));
    }

    /** Confirms one invalid row among several rejects the entire batch: nothing persists. */
    @Test
    void shouldRejectEntireBatchWhenOneRowIsInvalid() {
        StaffRepository staffRepository = mock(StaffRepository.class);
        DailyWorkRecordRepository recordRepository = mock(DailyWorkRecordRepository.class);
        Staff staffA = staff("STF-000001", "Nguyen", "Van A");
        Staff staffB = staff("STF-000002", "Tran", "Thi B");
        Staff staffC = staff("STF-000003", "Le", "Van C");
        when(staffRepository.findByActiveTrueOrderByStaffCodeAsc()).thenReturn(List.of(staffA, staffB, staffC));
        setCurrentUser(UUID.randomUUID());

        assertThrows(ResponseStatusException.class, () -> service(staffRepository, recordRepository).saveBulk(
                WORK_DATE,
                List.of(
                        line(staffA, LocalTime.of(8, 0), LocalTime.of(17, 0), null),
                        line(staffB, LocalTime.of(7, 30), null, null),
                        line(staffC, LocalTime.of(9, 0), LocalTime.of(18, 0), null))));

        verify(recordRepository, never()).save(any(DailyWorkRecord.class));
        verify(recordRepository, never()).delete(any(DailyWorkRecord.class));
    }

    /** Confirms a submitted line for a Staff member who is no longer active is rejected. */
    @Test
    void shouldRejectLineForNoLongerActiveStaff() {
        StaffRepository staffRepository = mock(StaffRepository.class);
        DailyWorkRecordRepository recordRepository = mock(DailyWorkRecordRepository.class);
        Staff staffA = staff("STF-000001", "Nguyen", "Van A");
        when(staffRepository.findByActiveTrueOrderByStaffCodeAsc()).thenReturn(List.of());
        setCurrentUser(UUID.randomUUID());

        ResponseStatusException exception = assertThrows(ResponseStatusException.class, () -> service(
                        staffRepository, recordRepository)
                .saveBulk(WORK_DATE, List.of(line(staffA, LocalTime.of(8, 0), LocalTime.of(17, 0), null))));

        assertEquals(400, exception.getStatusCode().value());
        verify(recordRepository, never()).save(any(DailyWorkRecord.class));
    }

    /** Confirms Work History returns only actual persisted records, newest work date first. */
    @Test
    void shouldReturnWorkHistoryNewestDateFirstWithoutSynthesizingMissingDates() {
        StaffRepository staffRepository = mock(StaffRepository.class);
        DailyWorkRecordRepository recordRepository = mock(DailyWorkRecordRepository.class);
        Staff staffA = staff("STF-000001", "Nguyen", "Van A");
        LocalDate from = LocalDate.of(2026, 9, 1);
        LocalDate to = LocalDate.of(2026, 9, 18);
        DailyWorkRecord newer = DailyWorkRecord.create(
                UUID.randomUUID(), staffA, LocalDate.of(2026, 9, 18), LocalTime.of(8, 0), LocalTime.of(17, 0), null);
        DailyWorkRecord older = DailyWorkRecord.create(
                UUID.randomUUID(), staffA, LocalDate.of(2026, 9, 16), LocalTime.of(7, 55), LocalTime.of(17, 0), "Note");
        when(recordRepository.findByStaffIdAndWorkDateBetweenOrderByWorkDateDesc(staffA.getId(), from, to))
                .thenReturn(List.of(newer, older));

        List<com.example.hotel.dto.common.response.DailyWorkRecordHistoryLine> history =
                service(staffRepository, recordRepository).history(staffA.getId(), from, to);

        assertEquals(2, history.size());
        assertEquals(LocalDate.of(2026, 9, 18), history.get(0).workDate());
        assertEquals(LocalDate.of(2026, 9, 16), history.get(1).workDate());
        assertEquals(LocalTime.of(8, 0), history.get(0).startTime());
        assertEquals(LocalTime.of(17, 0), history.get(0).endTime());
        assertEquals("9h00", history.get(0).workingTime());
        assertEquals("Note", history.get(1).notes());
    }

    /** Confirms an empty range with no matching records returns an empty Work History list. */
    @Test
    void shouldReturnEmptyWorkHistoryWhenNoRecordsInRange() {
        StaffRepository staffRepository = mock(StaffRepository.class);
        DailyWorkRecordRepository recordRepository = mock(DailyWorkRecordRepository.class);
        UUID staffId = UUID.randomUUID();
        LocalDate from = LocalDate.of(2026, 9, 1);
        LocalDate to = LocalDate.of(2026, 9, 18);
        when(recordRepository.findByStaffIdAndWorkDateBetweenOrderByWorkDateDesc(staffId, from, to))
                .thenReturn(List.of());

        List<com.example.hotel.dto.common.response.DailyWorkRecordHistoryLine> history =
                service(staffRepository, recordRepository).history(staffId, from, to);

        assertEquals(0, history.size());
    }

    /** Confirms historical Daily Work Record entries for a now-inactive Staff member remain queryable. */
    @Test
    void shouldReturnWorkHistoryForInactiveStaff() {
        StaffRepository staffRepository = mock(StaffRepository.class);
        DailyWorkRecordRepository recordRepository = mock(DailyWorkRecordRepository.class);
        Staff inactiveStaff = staff("STF-000009", "Le", "Van C");
        LocalDate from = LocalDate.of(2026, 8, 1);
        LocalDate to = LocalDate.of(2026, 8, 31);
        DailyWorkRecord historical = DailyWorkRecord.create(
                UUID.randomUUID(), inactiveStaff, LocalDate.of(2026, 8, 1), LocalTime.of(8, 0), LocalTime.of(17, 0), null);
        when(recordRepository.findByStaffIdAndWorkDateBetweenOrderByWorkDateDesc(inactiveStaff.getId(), from, to))
                .thenReturn(List.of(historical));

        List<com.example.hotel.dto.common.response.DailyWorkRecordHistoryLine> history =
                service(staffRepository, recordRepository).history(inactiveStaff.getId(), from, to);

        assertEquals(1, history.size());
        assertEquals(LocalDate.of(2026, 8, 1), history.get(0).workDate());
    }

    /** Creates a representative active Staff member. */
    private Staff staff(String staffCode, String firstName, String lastName) {
        return Staff.create(
                UUID.randomUUID(), staffCode, firstName, lastName, null, null, null, LocalDate.of(2026, 1, 1), null);
    }

    /** Builds one submitted Daily Work Record line for the given Staff member. */
    private DailyWorkRecordLineForm line(Staff staff, LocalTime start, LocalTime end, String notes) {
        DailyWorkRecordLineForm line = new DailyWorkRecordLineForm();
        line.setStaffId(staff.getId());
        line.setStaffCode(staff.getStaffCode());
        line.setStaffName(staff.getFirstName() + " " + staff.getLastName());
        line.setStartTime(start);
        line.setEndTime(end);
        line.setNotes(notes);
        return line;
    }

    /** Establishes the authenticated application user used for audit attribution. */
    private void setCurrentUser(UUID userId) {
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(new CurrentUser(userId, "attendance-manager"), null));
    }

    /** Creates the Daily Work Record service under test with mocked repositories. */
    private DailyWorkRecordService service(StaffRepository staffRepository, DailyWorkRecordRepository recordRepository) {
        return new DailyWorkRecordService(staffRepository, recordRepository);
    }
}
