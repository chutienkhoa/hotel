package com.example.hotel.dto.common.request;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import org.springframework.format.annotation.DateTimeFormat;

/**
 * Backs the Daily Work Record bulk entry screen: one selected work date plus one editable line
 * per active Staff member. A mutable class (not a record) so Spring MVC can bind the indexed
 * {@code lines} list from {@code lines[0].startTime}-style form field names.
 */
public class DailyWorkRecordFormRequest {

    @DateTimeFormat(iso = DateTimeFormat.ISO.DATE)
    private LocalDate workDate;

    private List<DailyWorkRecordLineForm> lines = new ArrayList<>();

    /**
     * Returns the selected work date.
     *
     * @return selected work date
     */
    public LocalDate getWorkDate() {
        return workDate;
    }

    /**
     * Sets the selected work date.
     *
     * @param workDate selected work date
     */
    public void setWorkDate(LocalDate workDate) {
        this.workDate = workDate;
    }

    /**
     * Returns the editable lines, one per active Staff member.
     *
     * @return editable Daily Work Record lines
     */
    public List<DailyWorkRecordLineForm> getLines() {
        return lines;
    }

    /**
     * Sets the editable lines, one per active Staff member.
     *
     * @param lines editable Daily Work Record lines
     */
    public void setLines(List<DailyWorkRecordLineForm> lines) {
        this.lines = lines;
    }
}
