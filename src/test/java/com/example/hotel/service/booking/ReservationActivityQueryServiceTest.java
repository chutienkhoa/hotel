package com.example.hotel.service.booking;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.example.hotel.dto.booking.response.ReservationActivityEntry;
import com.example.hotel.entity.common.AppUser;
import com.example.hotel.entity.common.AuditLog;
import com.example.hotel.repository.common.AppUserRepository;
import com.example.hotel.repository.common.AuditLogRepository;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * Verifies the Reservation Operational Timeline read path: correct Reservation scoping, stable
 * ordering, batched actor resolution (no N+1), and graceful behavior with no recorded activity.
 */
class ReservationActivityQueryServiceTest {

    /** Confirms only the requested Reservation's audit rows are returned, in repository order. */
    @Test
    void shouldReturnOnlyRequestedReservationActivityInRepositoryOrder() {
        AuditLogRepository auditLogRepository = mock(AuditLogRepository.class);
        AppUserRepository appUserRepository = mock(AppUserRepository.class);
        UUID reservationId = UUID.randomUUID();
        UUID userId = UUID.randomUUID();
        AuditLog first = new AuditLog(userId, "CREATE", reservationId, null, null);
        AuditLog second = new AuditLog(userId, "CONFIRM", reservationId, null, null);
        when(auditLogRepository.findByEntityTypeAndEntityIdOrderByCreatedAtAscIdAsc("RESERVATION", reservationId))
                .thenReturn(List.of(first, second));
        when(appUserRepository.findAllById(Set.of(userId))).thenReturn(List.of(appUser(userId, "staff01")));

        List<ReservationActivityEntry> entries =
                service(auditLogRepository, appUserRepository).findByReservationId(reservationId);

        assertEquals(List.of("CREATE", "CONFIRM"), entries.stream().map(ReservationActivityEntry::action).toList());
        assertEquals(List.of("staff01", "staff01"), entries.stream().map(ReservationActivityEntry::actorDisplay).toList());
        verify(auditLogRepository).findByEntityTypeAndEntityIdOrderByCreatedAtAscIdAsc(eq("RESERVATION"), eq(reservationId));
    }

    /** Confirms a Reservation with no recorded activity returns an empty, non-null list. */
    @Test
    void shouldReturnEmptyListForReservationWithNoActivity() {
        AuditLogRepository auditLogRepository = mock(AuditLogRepository.class);
        AppUserRepository appUserRepository = mock(AppUserRepository.class);
        UUID reservationId = UUID.randomUUID();
        when(auditLogRepository.findByEntityTypeAndEntityIdOrderByCreatedAtAscIdAsc("RESERVATION", reservationId))
                .thenReturn(List.of());

        List<ReservationActivityEntry> entries =
                service(auditLogRepository, appUserRepository).findByReservationId(reservationId);

        assertTrue(entries.isEmpty());
    }

    /** Confirms actor usernames are resolved in exactly one batched lookup, never one query per row. */
    @Test
    void shouldResolveActorsInOneBatchedLookupAvoidingNPlusOne() {
        AuditLogRepository auditLogRepository = mock(AuditLogRepository.class);
        AppUserRepository appUserRepository = mock(AppUserRepository.class);
        UUID reservationId = UUID.randomUUID();
        UUID firstUser = UUID.randomUUID();
        UUID secondUser = UUID.randomUUID();
        List<AuditLog> rows = List.of(
                new AuditLog(firstUser, "CREATE", reservationId, null, null),
                new AuditLog(secondUser, "CONFIRM", reservationId, null, null),
                new AuditLog(firstUser, "CHECK_IN", reservationId, null, null));
        when(auditLogRepository.findByEntityTypeAndEntityIdOrderByCreatedAtAscIdAsc("RESERVATION", reservationId))
                .thenReturn(rows);
        when(appUserRepository.findAllById(Set.of(firstUser, secondUser)))
                .thenReturn(List.of(appUser(firstUser, "staff01"), appUser(secondUser, "manager01")));

        service(auditLogRepository, appUserRepository).findByReservationId(reservationId);

        verify(appUserRepository, org.mockito.Mockito.times(1)).findAllById(any());
    }

    /** Confirms a deactivated actor (soft-deactivated, never hard-deleted) still resolves a display name. */
    @Test
    void shouldResolveDeactivatedActorDisplayName() {
        AuditLogRepository auditLogRepository = mock(AuditLogRepository.class);
        AppUserRepository appUserRepository = mock(AppUserRepository.class);
        UUID reservationId = UUID.randomUUID();
        UUID userId = UUID.randomUUID();
        AppUser deactivated = appUser(userId, "former-staff");
        deactivated.deactivate();
        when(auditLogRepository.findByEntityTypeAndEntityIdOrderByCreatedAtAscIdAsc("RESERVATION", reservationId))
                .thenReturn(List.of(new AuditLog(userId, "CANCEL", reservationId, null, null)));
        when(appUserRepository.findAllById(Set.of(userId))).thenReturn(List.of(deactivated));

        List<ReservationActivityEntry> entries =
                service(auditLogRepository, appUserRepository).findByReservationId(reservationId);

        assertEquals("former-staff", entries.get(0).actorDisplay());
    }

    /** Confirms an actor that cannot be resolved falls back to a safe placeholder instead of failing. */
    @Test
    void shouldFallBackSafelyWhenActorCannotBeResolved() {
        AuditLogRepository auditLogRepository = mock(AuditLogRepository.class);
        AppUserRepository appUserRepository = mock(AppUserRepository.class);
        UUID reservationId = UUID.randomUUID();
        UUID userId = UUID.randomUUID();
        when(auditLogRepository.findByEntityTypeAndEntityIdOrderByCreatedAtAscIdAsc("RESERVATION", reservationId))
                .thenReturn(List.of(new AuditLog(userId, "CREATE", reservationId, null, null)));
        when(appUserRepository.findAllById(Set.of(userId))).thenReturn(List.of());

        List<ReservationActivityEntry> entries =
                service(auditLogRepository, appUserRepository).findByReservationId(reservationId);

        assertEquals("—", entries.get(0).actorDisplay());
    }

    private ReservationActivityQueryService service(AuditLogRepository auditLogRepository, AppUserRepository appUsers) {
        return new ReservationActivityQueryService(auditLogRepository, appUsers);
    }

    /** Builds an AppUser fixture with the given identifier and username. */
    private AppUser appUser(UUID id, String username) {
        return new AppUser(id, username, "hash");
    }
}
