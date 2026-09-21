package com.example.hotel.service.room;

import com.example.hotel.entity.room.Room;
import com.example.hotel.entity.room.RoomInventoryOrigin;
import com.example.hotel.entity.room.RoomInventoryPeriod;
import com.example.hotel.entity.room.RoomUnavailableReason;
import com.example.hotel.repository.room.RoomInventoryPeriodRepository;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Keeps Room inventory history (RoomType and sellability over time) consistent with the Room itself.
 * Writers join the caller's Room transaction and must already hold the Room write lock, so a Room
 * change and its history change commit or roll back together.
 */
@Service
public class RoomInventoryHistoryService {

    private final RoomInventoryPeriodRepository periods;
    private final Clock clock;

    /**
     * Creates the history service.
     *
     * @param periods repository holding Room inventory periods
     * @param clock hotel business clock whose zone defines the hotel calendar date
     */
    public RoomInventoryHistoryService(RoomInventoryPeriodRepository periods, Clock clock) {
        this.periods = periods;
        this.clock = clock;
    }

    /**
     * Opens the first RECORDED period of a newly created Room.
     *
     * @param room newly created Room
     * @param at real Instant the Room started to exist
     * @param user authenticated user recorded as creator
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public void initialize(Room room, Instant at, UUID user) {
        periods.save(new RoomInventoryPeriod(
                room, room.getRoomType(), RoomUnavailableReason.fromStatus(room.getStatus()), at, user));
    }

    /**
     * Idempotently aligns the open period with the Room's current RoomType and sellability. Nothing
     * happens when both are unchanged, so status changes within sellable inventory create no history.
     * Otherwise the open period is closed and a replacement opened at the same Instant.
     *
     * @param room Room whose write lock the caller holds
     * @param at real Instant of the change, used as both the old {@code effectiveTo} and new {@code effectiveFrom}
     * @param user authenticated user recorded as updater/creator
     * @throws IllegalStateException if the Room has no single open period; history is never repaired silently
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public void sync(Room room, Instant at, UUID user) {
        List<RoomInventoryPeriod> open = periods.findOpenByRoomId(room.getId());
        if (open.size() != 1) {
            throw new IllegalStateException(
                    "Room " + room.getRoomNumber() + " must have exactly one open inventory period but has "
                            + open.size());
        }
        RoomInventoryPeriod current = open.get(0);
        RoomUnavailableReason reason = RoomUnavailableReason.fromStatus(room.getStatus());
        boolean sameType = current.getRoomType().getId().equals(room.getRoomType().getId());
        if (sameType && current.getUnavailableReason() == reason) {
            return;
        }
        current.close(at, user);
        // Hibernate flushes inserts before updates; closing first keeps the one-open-period index satisfied.
        periods.saveAndFlush(current);
        periods.save(new RoomInventoryPeriod(room, room.getRoomType(), reason, at, user));
    }

    /**
     * Returns the hotel date of the earliest BOOTSTRAP period, the first date for which the
     * foundation asserts an inventory state. Rooms recorded later do not move this boundary.
     *
     * @return the hotel-zone date, or empty when the database has no BOOTSTRAP period
     */
    @Transactional(readOnly = true)
    public Optional<LocalDate> bootstrapHistoryStart() {
        Instant earliest = periods.findEarliestEffectiveFromByOrigin(RoomInventoryOrigin.BOOTSTRAP);
        return Optional.ofNullable(earliest).map(instant -> LocalDate.ofInstant(instant, clock.getZone()));
    }

    /**
     * Returns the first calendar month fully covered by BOOTSTRAP-era history: the month of the
     * history start when it starts on the 1st, otherwise the following month.
     *
     * @return the first fully supported month, or empty when the database has no BOOTSTRAP period
     */
    @Transactional(readOnly = true)
    public Optional<YearMonth> firstFullySupportedMonth() {
        return bootstrapHistoryStart().map(RoomInventoryHistoryService::firstFullySupportedMonth);
    }

    /**
     * Derives the first fully supported calendar month from a history start date.
     *
     * @param historyStart hotel date the inventory history begins
     * @return the month of {@code historyStart} if it is the 1st, otherwise the next month
     */
    public static YearMonth firstFullySupportedMonth(LocalDate historyStart) {
        YearMonth month = YearMonth.from(historyStart);
        return historyStart.getDayOfMonth() == 1 ? month : month.plusMonths(1);
    }
}
