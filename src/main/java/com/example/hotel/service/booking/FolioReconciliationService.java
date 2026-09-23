package com.example.hotel.service.booking;

import com.example.hotel.dto.booking.response.FolioReconciliationResponse;
import com.example.hotel.dto.booking.response.FolioReconciliationResponse.Issue;
import com.example.hotel.dto.booking.response.FolioReconciliationResponse.IssueType;
import com.example.hotel.entity.booking.Charge;
import com.example.hotel.entity.booking.ChargeStatus;
import com.example.hotel.entity.booking.ChargeType;
import com.example.hotel.entity.booking.ReservationRoom;
import com.example.hotel.entity.booking.Stay;
import com.example.hotel.entity.booking.StayExtensionRoom;
import com.example.hotel.entity.common.AdditionalRevenue;
import com.example.hotel.entity.common.AdditionalRevenueStatus;
import com.example.hotel.repository.booking.ChargeRepository;
import com.example.hotel.repository.booking.StayExtensionRoomRepository;
import com.example.hotel.repository.booking.StayRepository;
import com.example.hotel.repository.common.AdditionalRevenueRepository;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

/**
 * Read-only reconciliation of a Stay's folio with its revenue sources: original ROOM charges against
 * {@code ReservationRoom}, extension ROOM charges against {@code StayExtensionRoom}, and guest service charges against
 * their linked {@code AdditionalRevenue}. It never repairs data and is never a check-out condition. Four bounded
 * queries per Stay, no per-charge lookups.
 */
@Service
public class FolioReconciliationService {

    private final StayRepository stays;
    private final ChargeRepository charges;
    private final StayExtensionRoomRepository extensionRooms;
    private final AdditionalRevenueRepository additionalRevenues;

    /**
     * Creates the service.
     *
     * @param stays Stay repository
     * @param charges Charge repository
     * @param extensionRooms extension-line repository
     * @param additionalRevenues Additional Revenue repository
     */
    public FolioReconciliationService(
            StayRepository stays,
            ChargeRepository charges,
            StayExtensionRoomRepository extensionRooms,
            AdditionalRevenueRepository additionalRevenues) {
        this.stays = stays;
        this.charges = charges;
        this.extensionRooms = extensionRooms;
        this.additionalRevenues = additionalRevenues;
    }

    /**
     * Reconciles one Stay.
     *
     * @param stayId Stay identifier
     * @return the diagnostic result
     * @throws ResponseStatusException 404 when the Stay does not exist
     */
    @Transactional(readOnly = true)
    public FolioReconciliationResponse reconcile(UUID stayId) {
        Stay stay = stays.findById(stayId).orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Stay not found"));
        List<ReservationRoom> booked = stay.getReservation().getRooms();
        List<Charge> stayCharges = charges.findByStayIdWithSource(stayId);
        List<StayExtensionRoom> lines = extensionRooms.findByStayIdWithCharge(stayId);

        List<Issue> issues = new ArrayList<>();
        Map<UUID, Charge> originalBySource = new HashMap<>();
        Set<UUID> extensionChargeIds = new HashSet<>();
        for (StayExtensionRoom line : lines) {
            extensionChargeIds.add(line.getCharge().getId());
        }
        for (Charge charge : stayCharges) {
            if (charge.getType() == ChargeType.ROOM
                    && charge.getSourceReservationRoom() != null
                    && !extensionChargeIds.contains(charge.getId())) {
                originalBySource.put(charge.getSourceReservationRoom().getId(), charge);
            }
        }

        BigDecimal expectedOriginal = BigDecimal.ZERO;
        BigDecimal actualOriginal = BigDecimal.ZERO;
        Set<UUID> bookedIds = new HashSet<>();
        for (ReservationRoom room : booked) {
            bookedIds.add(room.getId());
            expectedOriginal = expectedOriginal.add(room.getTotalAmount());
            Charge charge = originalBySource.get(room.getId());
            if (charge == null) {
                issues.add(new Issue(IssueType.MISSING_ORIGINAL_ROOM_CHARGE, room.getId(), room.getTotalAmount(), null));
                continue;
            }
            actualOriginal = actualOriginal.add(charge.getAmount());
            if (charge.getAmount().compareTo(room.getTotalAmount()) != 0) {
                issues.add(new Issue(
                        IssueType.ORIGINAL_ROOM_CHARGE_AMOUNT_MISMATCH, charge.getId(), room.getTotalAmount(), charge.getAmount()));
            }
        }

        BigDecimal expectedExtension = BigDecimal.ZERO;
        BigDecimal actualExtension = BigDecimal.ZERO;
        for (StayExtensionRoom line : lines) {
            expectedExtension = expectedExtension.add(line.getAmount());
            Charge charge = line.getCharge();
            if (charge == null) {
                issues.add(new Issue(IssueType.MISSING_EXTENSION_CHARGE, line.getId(), line.getAmount(), null));
                continue;
            }
            actualExtension = actualExtension.add(charge.getAmount());
            if (charge.getAmount().compareTo(line.getAmount()) != 0) {
                issues.add(new Issue(
                        IssueType.EXTENSION_CHARGE_AMOUNT_MISMATCH, charge.getId(), line.getAmount(), charge.getAmount()));
            }
        }

        List<UUID> serviceChargeIds = new ArrayList<>();
        for (Charge charge : stayCharges) {
            if (charge.getType() == ChargeType.ROOM) {
                boolean linkedOriginal = charge.getSourceReservationRoom() != null
                        && bookedIds.contains(charge.getSourceReservationRoom().getId());
                if (!linkedOriginal && !extensionChargeIds.contains(charge.getId())) {
                    issues.add(new Issue(IssueType.ORPHAN_ROOM_CHARGE, charge.getId(), null, charge.getAmount()));
                }
            } else if (charge.getType().isGuestServiceRevenue()) {
                serviceChargeIds.add(charge.getId());
            }
        }
        if (!serviceChargeIds.isEmpty()) {
            Map<UUID, AdditionalRevenue> revenueByCharge = new HashMap<>();
            for (AdditionalRevenue revenue : additionalRevenues.findByChargeIdIn(serviceChargeIds)) {
                revenueByCharge.put(revenue.getCharge().getId(), revenue);
            }
            for (Charge charge : stayCharges) {
                if (!serviceChargeIds.contains(charge.getId())) {
                    continue;
                }
                AdditionalRevenue revenue = revenueByCharge.get(charge.getId());
                if (revenue == null) {
                    issues.add(new Issue(IssueType.SERVICE_CHARGE_WITHOUT_REVENUE, charge.getId(), charge.getAmount(), null));
                    continue;
                }
                // A VOIDED Charge's linked revenue is expected to be VOIDED too (the Charge void workflow voids both
                // atomically); an ACTIVE Charge's linked revenue is expected to stay RECORDED. Either combination
                // still crossing is a genuine inconsistency worth reporting.
                AdditionalRevenueStatus expectedRevenueStatus = charge.getStatus() == ChargeStatus.VOIDED
                        ? AdditionalRevenueStatus.VOIDED
                        : AdditionalRevenueStatus.RECORDED;
                if (revenue.getStatus() != expectedRevenueStatus
                        || revenue.getAmount().compareTo(charge.getAmount()) != 0) {
                    issues.add(new Issue(
                            IssueType.SERVICE_REVENUE_AMOUNT_MISMATCH, charge.getId(), charge.getAmount(), revenue.getAmount()));
                }
            }
        }

        return new FolioReconciliationResponse(
                stayId,
                issues.isEmpty() ? "MATCHED" : "MISMATCH",
                expectedOriginal,
                expectedExtension,
                expectedOriginal.add(expectedExtension),
                actualOriginal,
                actualExtension,
                actualOriginal.add(actualExtension),
                List.copyOf(issues));
    }
}
