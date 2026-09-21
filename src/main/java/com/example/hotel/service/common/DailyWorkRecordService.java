package com.example.hotel.service.common;

import com.example.hotel.dto.common.request.DailyWorkRecordLineForm;
import com.example.hotel.dto.common.response.DailyWorkRecordHistoryLine;
import com.example.hotel.entity.common.DailyWorkRecord;
import com.example.hotel.entity.common.Staff;
import com.example.hotel.repository.common.DailyWorkRecordRepository;
import com.example.hotel.repository.common.StaffRepository;
import com.example.hotel.security.CurrentUser;
import com.example.hotel.security.SessionUserPrincipal;
import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

/**
 * Manages the Daily Work Record bulk entry screen: one manually-entered start/end time per
 * active Staff member per calendar day. Reuses {@link StaffRepository} directly (same Staff
 * Management domain module) rather than duplicating Staff lookups through another service.
 */
@Service
public class DailyWorkRecordService {

    private final StaffRepository staffRepository;
    private final DailyWorkRecordRepository dailyWorkRecordRepository;

    /**
     * Creates the Daily Work Record service with its persistence collaborators.
     *
     * @param staffRepository repository used to load active Staff members
     * @param dailyWorkRecordRepository repository used to persist Daily Work Record entries
     */
    public DailyWorkRecordService(
            StaffRepository staffRepository, DailyWorkRecordRepository dailyWorkRecordRepository) {
        this.staffRepository = staffRepository;
        this.dailyWorkRecordRepository = dailyWorkRecordRepository;
    }

    /**
     * Builds one editable line per active Staff member for the selected work date, prefilled
     * with any existing Daily Work Record values and a freshly derived Working Time.
     *
     * @param workDate selected work date
     * @return one line per active Staff member, in Staff Code order
     */
    @Transactional(readOnly = true)
    public List<DailyWorkRecordLineForm> loadLines(LocalDate workDate) {
        List<Staff> activeStaff = staffRepository.findByActiveTrueOrderByStaffCodeAsc();
        Map<UUID, DailyWorkRecord> existingByStaffId = new LinkedHashMap<>();
        for (DailyWorkRecord record : dailyWorkRecordRepository.findByWorkDate(workDate)) {
            existingByStaffId.put(record.getStaff().getId(), record);
        }
        List<DailyWorkRecordLineForm> lines = new ArrayList<>();
        for (Staff staff : activeStaff) {
            DailyWorkRecord existing = existingByStaffId.get(staff.getId());
            DailyWorkRecordLineForm line = new DailyWorkRecordLineForm();
            line.setStaffId(staff.getId());
            line.setStaffCode(staff.getStaffCode());
            line.setStaffName(staff.getFirstName() + " " + staff.getLastName());
            if (existing != null) {
                line.setStartTime(existing.getStartTime());
                line.setEndTime(existing.getEndTime());
                line.setNotes(existing.getNotes());
            }
            line.setWorkingTime(formatWorkingTime(line.getStartTime(), line.getEndTime()));
            lines.add(line);
        }
        return lines;
    }

    /**
     * Loads the read-only Work History for one Staff member within an inclusive work date range,
     * newest work date first. Only calendar dates with an actual persisted Daily Work Record are
     * returned; a missing date is never synthesized into a row.
     *
     * @param staffId Staff identifier
     * @param fromDate inclusive lower bound of the work date range
     * @param toDate inclusive upper bound of the work date range
     * @return matching Work History rows, ordered by work date descending
     */
    @Transactional(readOnly = true)
    public List<DailyWorkRecordHistoryLine> history(UUID staffId, LocalDate fromDate, LocalDate toDate) {
        return dailyWorkRecordRepository
                .findByStaffIdAndWorkDateBetweenOrderByWorkDateDesc(staffId, fromDate, toDate)
                .stream()
                .map(record -> new DailyWorkRecordHistoryLine(
                        record.getWorkDate(),
                        record.getStartTime(),
                        record.getEndTime(),
                        formatWorkingTime(record.getStartTime(), record.getEndTime()),
                        record.getNotes()))
                .toList();
    }

    /**
     * Recomputes the display-only Working Time for every line, without touching any other field.
     * Used to refresh the derived value before redisplaying the form after a validation failure.
     *
     * @param lines submitted lines to refresh in place
     */
    public void refreshWorkingTime(List<DailyWorkRecordLineForm> lines) {
        for (DailyWorkRecordLineForm line : lines) {
            line.setWorkingTime(formatWorkingTime(line.getStartTime(), line.getEndTime()));
        }
    }

    /**
     * Saves every submitted line for one work date as a single atomic operation. The complete
     * submission is validated before any row is created, updated, or removed, so one invalid
     * line safely rejects the entire save.
     *
     * <p>Per line: both Start and End blank removes any existing record for that Staff/date;
     * both populated with Start strictly before End creates or updates the existing record;
     * exactly one populated, or Start not strictly before End, is a validation failure.</p>
     *
     * @param workDate selected work date
     * @param lines submitted lines, one per active Staff member at form-load time
     * @throws ResponseStatusException if any line is invalid, or if a submitted Staff member is
     *     no longer active
     */
    @Transactional
    public void saveBulk(LocalDate workDate, List<DailyWorkRecordLineForm> lines) {
        if (workDate == null) {
            throw badRequest("Work date is required.");
        }
        Map<UUID, Staff> activeStaffById = new LinkedHashMap<>();
        for (Staff staff : staffRepository.findByActiveTrueOrderByStaffCodeAsc()) {
            activeStaffById.put(staff.getId(), staff);
        }

        List<String> errors = new ArrayList<>();
        for (DailyWorkRecordLineForm line : lines) {
            validateLine(line, activeStaffById, errors);
        }
        if (!errors.isEmpty()) {
            throw badRequest(String.join(" ", errors));
        }

        CurrentUser user = currentUser();
        try {
            for (DailyWorkRecordLineForm line : lines) {
                applyLine(workDate, line, activeStaffById, user.id());
            }
        } catch (DataIntegrityViolationException exception) {
            throw conflict("Daily Work Record could not be saved because it changed concurrently. Please retry.");
        }
    }

    /**
     * Validates one submitted line, appending a Staff-attributed message to {@code errors} when
     * invalid. Never mutates any persisted state.
     *
     * @param line submitted line to validate
     * @param activeStaffById currently active Staff members, keyed by identifier
     * @param errors accumulator for Staff-attributed validation messages
     */
    private void validateLine(DailyWorkRecordLineForm line, Map<UUID, Staff> activeStaffById, List<String> errors) {
        String label = line.getStaffName() != null ? line.getStaffName() : "This Staff member";
        boolean startBlank = line.getStartTime() == null;
        boolean endBlank = line.getEndTime() == null;
        if (startBlank && endBlank) {
            return;
        }
        if (startBlank || endBlank) {
            errors.add(label + ": Start and End time must both be provided, or both left blank.");
            return;
        }
        if (!line.getStartTime().isBefore(line.getEndTime())) {
            errors.add(label + ": Start time must be before End time.");
            return;
        }
        if (line.getStaffId() == null || !activeStaffById.containsKey(line.getStaffId())) {
            errors.add(label + ": Staff member is no longer active.");
        }
    }

    /**
     * Applies one already-validated line: removes an existing record when both fields were
     * cleared, otherwise creates or updates the existing record for that Staff/date.
     *
     * @param workDate selected work date
     * @param line already-validated submitted line
     * @param activeStaffById currently active Staff members, keyed by identifier
     * @param auditUserId authenticated user responsible for the change
     */
    private void applyLine(
            LocalDate workDate, DailyWorkRecordLineForm line, Map<UUID, Staff> activeStaffById, UUID auditUserId) {
        Optional<DailyWorkRecord> existing =
                dailyWorkRecordRepository.findByStaffIdAndWorkDate(line.getStaffId(), workDate);
        boolean bothBlank = line.getStartTime() == null && line.getEndTime() == null;
        if (bothBlank) {
            existing.ifPresent(dailyWorkRecordRepository::delete);
            return;
        }
        String notes = blankToNull(line.getNotes());
        if (existing.isPresent()) {
            DailyWorkRecord record = existing.get();
            record.update(line.getStartTime(), line.getEndTime(), notes);
            record.audit(auditUserId);
            dailyWorkRecordRepository.save(record);
            return;
        }
        Staff staff = activeStaffById.get(line.getStaffId());
        DailyWorkRecord record =
                DailyWorkRecord.create(UUID.randomUUID(), staff, workDate, line.getStartTime(), line.getEndTime(), notes);
        record.audit(auditUserId);
        dailyWorkRecordRepository.save(record);
    }

    /**
     * Formats the display-only derived Working Time between a start and end time.
     *
     * @param start work start time, or {@code null}
     * @param end work end time, or {@code null}
     * @return {@code "9h00"}-style text, or {@code null} when no valid pair is present
     */
    private String formatWorkingTime(LocalTime start, LocalTime end) {
        if (start == null || end == null || !start.isBefore(end)) {
            return null;
        }
        Duration duration = Duration.between(start, end);
        return duration.toHours() + "h" + "%02d".formatted(duration.toMinutesPart());
    }

    /**
     * Converts a blank optional form value to {@code null} so it is stored consistently.
     *
     * @param value raw optional submitted value
     * @return the trimmed value, or {@code null} when absent or blank
     */
    private String blankToNull(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }

    /**
     * Resolves the current JWT or session principal to the audit identity used by persisted
     * entities.
     *
     * @return the authenticated application user
     * @throws ResponseStatusException if the active principal is not an application user
     */
    private CurrentUser currentUser() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        Object principal = authentication == null ? null : authentication.getPrincipal();
        if (principal instanceof CurrentUser currentUser) {
            return currentUser;
        }
        if (principal instanceof SessionUserPrincipal sessionUserPrincipal) {
            return new CurrentUser(sessionUserPrincipal.id(), sessionUserPrincipal.getUsername());
        }
        throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Unauthenticated user");
    }

    /**
     * Creates a standard bad-request response for a rejected bulk submission.
     *
     * @param message browser-safe validation message
     * @return bad-request response exception
     */
    private ResponseStatusException badRequest(String message) {
        return new ResponseStatusException(HttpStatus.BAD_REQUEST, message);
    }

    /**
     * Creates a standard conflict response.
     *
     * @param message state-conflict message
     * @return conflict response exception
     */
    private ResponseStatusException conflict(String message) {
        return new ResponseStatusException(HttpStatus.CONFLICT, message);
    }
}
