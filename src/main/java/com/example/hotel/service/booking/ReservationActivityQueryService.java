package com.example.hotel.service.booking;

import com.example.hotel.dto.booking.response.ReservationActivityEntry;
import com.example.hotel.entity.booking.ReservationStatus;
import com.example.hotel.entity.common.AppUser;
import com.example.hotel.entity.common.AuditLog;
import com.example.hotel.repository.common.AppUserRepository;
import com.example.hotel.repository.common.AuditLogRepository;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Provides the read-only Reservation Operational Timeline v1: the existing {@link AuditLog} rows
 * already written against a Reservation's journey (Reservation, Room Change, Stay Extension,
 * Prepayment/Payment), in stable chronological order. AuditLog remains the source of truth for
 * "what/when/who"; financial amounts are never read from it (see {@link ChargeService} and
 * {@link PaymentService} for the authoritative financial data).
 */
@Service
public class ReservationActivityQueryService {

    private static final String RESERVATION_ENTITY_TYPE = "RESERVATION";
    /**
     * Audit actions whose recorded new value is the resulting Reservation status: the lifecycle transitions, plus the
     * draft edit, which is audited as {@code DRAFT -> DRAFT} and so states the status it left the Reservation in.
     */
    private static final Set<String> LIFECYCLE_ACTIONS =
            Set.of("CREATE", "UPDATE", "CONFIRM", "CANCEL", "NO_SHOW", "CHECK_IN", "CHECK_OUT");

    private final AuditLogRepository auditLogRepository;
    private final AppUserRepository appUsers;

    /**
     * Creates the query service with its persistence dependencies.
     *
     * @param auditLogRepository repository used to read the existing Reservation audit trail
     * @param appUsers repository used to resolve each entry's actor username, batched to avoid N+1
     */
    public ReservationActivityQueryService(AuditLogRepository auditLogRepository, AppUserRepository appUsers) {
        this.auditLogRepository = auditLogRepository;
        this.appUsers = appUsers;
    }

    /**
     * Lists a Reservation's complete Operational Timeline in stable chronological order. A
     * Reservation with no recorded activity yields an empty list.
     *
     * @param reservationId Reservation identifier
     * @return the Reservation's activity entries, oldest first
     */
    @Transactional(readOnly = true)
    public List<ReservationActivityEntry> findByReservationId(UUID reservationId) {
        List<AuditLog> rows = auditLogRepository
                .findByEntityTypeAndEntityIdOrderByCreatedAtAscIdAsc(RESERVATION_ENTITY_TYPE, reservationId);
        Map<UUID, String> usernamesById = resolveUsernames(rows);
        List<String> statuses = statusesAtTime(rows);
        List<ReservationActivityEntry> entries = new ArrayList<>(rows.size());
        for (int i = 0; i < rows.size(); i++) {
            AuditLog row = rows.get(i);
            entries.add(new ReservationActivityEntry(
                    row.getCreatedAt(), usernamesById.getOrDefault(row.getUserId(), "—"), row.getAction(),
                    statuses.get(i)));
        }
        return List.copyOf(entries);
    }

    /**
     * Establishes the Reservation status in force at each row from the audit trail alone. The audit log stores no
     * per-row status column, but every status transition is audited with the resulting status as its new value
     * ({@link #LIFECYCLE_ACTIONS}), and a Reservation's status changes only through those transitions. A lifecycle
     * row therefore carries the status it produced; any other row carries the status of the latest lifecycle row
     * before it, and a row before the first lifecycle row falls back to that row's recorded previous status. A row
     * whose status the trail does not establish is {@code null}: the Reservation's current status is never used.
     *
     * @param rows audit rows, oldest first
     * @return one status name (or {@code null}) per row
     */
    private static List<String> statusesAtTime(List<AuditLog> rows) {
        List<String> statuses = new ArrayList<>(rows.size());
        String current = null;
        int firstLifecycle = -1;
        for (int i = 0; i < rows.size(); i++) {
            AuditLog row = rows.get(i);
            String produced = LIFECYCLE_ACTIONS.contains(row.getAction()) ? statusName(row.getNewValue()) : null;
            if (produced != null) {
                current = produced;
                if (firstLifecycle < 0) {
                    firstLifecycle = i;
                }
            }
            statuses.add(current);
        }
        if (firstLifecycle > 0) {
            String before = statusName(rows.get(firstLifecycle).getOldValue());
            for (int i = 0; i < firstLifecycle; i++) {
                statuses.set(i, before);
            }
        }
        return statuses;
    }

    /** @return the canonical Reservation status name for a stored value, or {@code null} when it is not one */
    private static String statusName(String value) {
        if (value == null) {
            return null;
        }
        try {
            return ReservationStatus.valueOf(value).name();
        } catch (IllegalArgumentException notAStatus) {
            return null;
        }
    }

    /** Batch-resolves each row's actor username in one query, avoiding an N+1 lookup per row. */
    private Map<UUID, String> resolveUsernames(List<AuditLog> rows) {
        Set<UUID> userIds = rows.stream().map(AuditLog::getUserId).collect(Collectors.toSet());
        return appUsers.findAllById(userIds).stream()
                .collect(Collectors.toMap(AppUser::getId, AppUser::getUsername));
    }
}
