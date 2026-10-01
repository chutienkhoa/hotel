package com.example.hotel.repository.common;

import com.example.hotel.entity.common.DailyWorkRecord;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

/** Provides persistence access to Daily Work Record entries. */
public interface DailyWorkRecordRepository extends JpaRepository<DailyWorkRecord, UUID> {

    /**
     * Finds the at-most-one Daily Work Record for one Staff member on one work date.
     *
     * @param staffId Staff identifier
     * @param workDate calendar day to look up
     * @return the matching record, when one exists
     */
    Optional<DailyWorkRecord> findByStaffIdAndWorkDate(UUID staffId, LocalDate workDate);

    /**
     * Finds every Daily Work Record for one calendar day, for populating the bulk entry screen.
     *
     * @param workDate calendar day to load
     * @return every Daily Work Record recorded for that day
     */
    List<DailyWorkRecord> findByWorkDate(LocalDate workDate);

    /**
     * Finds every Daily Work Record for one Staff member within an inclusive date range, newest
     * work date first, for the Staff Detail Work History view.
     *
     * @param staffId Staff identifier
     * @param fromDate inclusive lower bound of the work date range
     * @param toDate inclusive upper bound of the work date range
     * @return matching Daily Work Record entries, ordered by work date descending
     */
    List<DailyWorkRecord> findByStaffIdAndWorkDateBetweenOrderByWorkDateDesc(
            UUID staffId, LocalDate fromDate, LocalDate toDate);
}
