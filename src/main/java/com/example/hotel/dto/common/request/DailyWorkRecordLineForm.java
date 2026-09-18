package com.example.hotel.dto.common.request;

import java.time.LocalTime;
import java.util.UUID;
import org.springframework.format.annotation.DateTimeFormat;

/**
 * Represents one editable Daily Work Record line for one Staff member on the bulk entry screen.
 * This is a mutable form-binding object (not a record) because Spring MVC binds an indexed
 * {@code List<DailyWorkRecordLineForm>} from {@code lines[0].startTime}-style form field names,
 * which requires JavaBean getters/setters and a no-argument constructor.
 *
 * <p>{@code staffCode}/{@code staffName} are round-tripped as hidden form fields purely for
 * redisplaying the Staff's identity after a validation failure, without a second lookup.
 * {@code workingTime} is a display-only derived value recomputed server-side before every
 * render; it is never authoritative and is never persisted.</p>
 */
public class DailyWorkRecordLineForm {

    private UUID staffId;
    private String staffCode;
    private String staffName;

    @DateTimeFormat(pattern = "HH:mm")
    private LocalTime startTime;

    @DateTimeFormat(pattern = "HH:mm")
    private LocalTime endTime;

    private String notes;
    private String workingTime;

    /**
     * Returns the Staff identifier this line belongs to.
     *
     * @return Staff identifier
     */
    public UUID getStaffId() {
        return staffId;
    }

    /**
     * Sets the Staff identifier this line belongs to.
     *
     * @param staffId Staff identifier
     */
    public void setStaffId(UUID staffId) {
        this.staffId = staffId;
    }

    /**
     * Returns the Staff Code shown next to this line.
     *
     * @return Staff Code
     */
    public String getStaffCode() {
        return staffCode;
    }

    /**
     * Sets the Staff Code shown next to this line.
     *
     * @param staffCode Staff Code
     */
    public void setStaffCode(String staffCode) {
        this.staffCode = staffCode;
    }

    /**
     * Returns the Staff display name shown next to this line.
     *
     * @return Staff display name
     */
    public String getStaffName() {
        return staffName;
    }

    /**
     * Sets the Staff display name shown next to this line.
     *
     * @param staffName Staff display name
     */
    public void setStaffName(String staffName) {
        this.staffName = staffName;
    }

    /**
     * Returns the entered work start time, or {@code null} when left blank.
     *
     * @return start time, or {@code null}
     */
    public LocalTime getStartTime() {
        return startTime;
    }

    /**
     * Sets the entered work start time.
     *
     * @param startTime start time, or {@code null} when left blank
     */
    public void setStartTime(LocalTime startTime) {
        this.startTime = startTime;
    }

    /**
     * Returns the entered work end time, or {@code null} when left blank.
     *
     * @return end time, or {@code null}
     */
    public LocalTime getEndTime() {
        return endTime;
    }

    /**
     * Sets the entered work end time.
     *
     * @param endTime end time, or {@code null} when left blank
     */
    public void setEndTime(LocalTime endTime) {
        this.endTime = endTime;
    }

    /**
     * Returns the optional entered notes.
     *
     * @return notes, or {@code null}
     */
    public String getNotes() {
        return notes;
    }

    /**
     * Sets the optional entered notes.
     *
     * @param notes notes, or {@code null}
     */
    public void setNotes(String notes) {
        this.notes = notes;
    }

    /**
     * Returns the display-only derived Working Time (e.g. {@code 9h00}), or {@code null} when no
     * complete start/end pair is present.
     *
     * @return derived Working Time display text, or {@code null}
     */
    public String getWorkingTime() {
        return workingTime;
    }

    /**
     * Sets the display-only derived Working Time. Never authoritative, never persisted.
     *
     * @param workingTime derived Working Time display text
     */
    public void setWorkingTime(String workingTime) {
        this.workingTime = workingTime;
    }
}
