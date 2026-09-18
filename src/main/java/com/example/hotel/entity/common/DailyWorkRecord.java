package com.example.hotel.entity.common;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.UUID;

/**
 * Represents one manually-entered working start/end time for one {@link Staff} member on one
 * calendar day. A Staff member has at most one {@code DailyWorkRecord} per {@code workDate},
 * enforced by both this entity and a database unique constraint. V1 supports same-day work only
 * (no overnight shifts) and does not track breaks, multiple sessions, or automatic attendance.
 */
@Entity
@Table(name = "daily_work_record")
public class DailyWorkRecord extends AuditedEntity {

    @Id
    private UUID id;

    @ManyToOne(optional = false, fetch = FetchType.LAZY)
    @JoinColumn(name = "staff_id", nullable = false)
    private Staff staff;

    @Column(name = "work_date", nullable = false)
    private LocalDate workDate;

    @Column(name = "start_time", nullable = false)
    private LocalTime startTime;

    @Column(name = "end_time", nullable = false)
    private LocalTime endTime;

    private String notes;

    /** Creates an empty DailyWorkRecord instance for JPA. */
    protected DailyWorkRecord() {}

    /**
     * Creates a new Daily Work Record for one Staff member on one work date.
     *
     * @param id Daily Work Record identifier
     * @param staff Staff member this record belongs to
     * @param workDate calendar day this record covers
     * @param startTime work start time, strictly before {@code endTime}
     * @param endTime work end time, strictly after {@code startTime}
     * @param notes optional notes
     * @return the new Daily Work Record
     * @throws IllegalStateException if {@code startTime} is not strictly before {@code endTime}
     */
    public static DailyWorkRecord create(
            UUID id, Staff staff, LocalDate workDate, LocalTime startTime, LocalTime endTime, String notes) {
        DailyWorkRecord record = new DailyWorkRecord();
        record.id = id;
        record.staff = staff;
        record.workDate = workDate;
        record.update(startTime, endTime, notes);
        return record;
    }

    /**
     * Replaces this record's start/end time and notes. The owning Staff member and work date are
     * never changed by this operation.
     *
     * @param startTime replacement work start time, strictly before {@code endTime}
     * @param endTime replacement work end time, strictly after {@code startTime}
     * @param notes optional replacement notes
     * @throws IllegalStateException if {@code startTime} is not strictly before {@code endTime}
     */
    public void update(LocalTime startTime, LocalTime endTime, String notes) {
        if (startTime == null || endTime == null || !startTime.isBefore(endTime)) {
            throw new IllegalStateException("Start time must be before End time.");
        }
        this.startTime = startTime;
        this.endTime = endTime;
        this.notes = notes;
    }

    /**
     * Returns the Daily Work Record identifier.
     *
     * @return Daily Work Record identifier
     */
    public UUID getId() {
        return id;
    }

    /**
     * Returns the owning Staff member.
     *
     * @return owning Staff member
     */
    public Staff getStaff() {
        return staff;
    }

    /**
     * Returns the calendar day this record covers.
     *
     * @return work date
     */
    public LocalDate getWorkDate() {
        return workDate;
    }

    /**
     * Returns the recorded work start time.
     *
     * @return start time
     */
    public LocalTime getStartTime() {
        return startTime;
    }

    /**
     * Returns the recorded work end time.
     *
     * @return end time
     */
    public LocalTime getEndTime() {
        return endTime;
    }

    /**
     * Returns the optional notes for this record.
     *
     * @return notes, or {@code null} when not supplied
     */
    public String getNotes() {
        return notes;
    }
}
