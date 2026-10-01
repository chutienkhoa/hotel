package com.example.hotel.service.booking;

import com.example.hotel.dto.booking.response.ReservationActivityEntry;
import com.example.hotel.entity.common.AppUser;
import com.example.hotel.entity.common.AuditLog;
import com.example.hotel.repository.common.AppUserRepository;
import com.example.hotel.repository.common.AuditLogRepository;
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
        return rows.stream()
                .map(row -> new ReservationActivityEntry(
                        row.getCreatedAt(), usernamesById.getOrDefault(row.getUserId(), "—"), row.getAction()))
                .toList();
    }

    /** Batch-resolves each row's actor username in one query, avoiding an N+1 lookup per row. */
    private Map<UUID, String> resolveUsernames(List<AuditLog> rows) {
        Set<UUID> userIds = rows.stream().map(AuditLog::getUserId).collect(Collectors.toSet());
        return appUsers.findAllById(userIds).stream()
                .collect(Collectors.toMap(AppUser::getId, AppUser::getUsername));
    }
}
