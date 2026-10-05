package com.example.hotel.service.booking;

import com.example.hotel.entity.booking.PaymentStatus;
import com.example.hotel.repository.booking.ChargeRepository;
import com.example.hotel.repository.booking.PaymentRepository;
import java.math.BigDecimal;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Calculates the authoritative Charge and PAID-Payment totals for a Stay. */
@Service
public class StayBalanceService {

    private final ChargeRepository chargeRepository;
    private final PaymentRepository paymentRepository;

    /**
     * Creates the Stay-balance calculator with its aggregate repositories.
     *
     * @param chargeRepository repository used to sum authoritative Charge amounts
     * @param paymentRepository repository used to sum PAID Payment applied amounts
     */
    public StayBalanceService(
            ChargeRepository chargeRepository, PaymentRepository paymentRepository) {
        this.chargeRepository = chargeRepository;
        this.paymentRepository = paymentRepository;
    }

    /**
     * Calculates the outstanding balance for a Stay.
     *
     * <p>Both aggregated totals are already denominated in the Reservation's currency — Charge
     * amounts always are, and Payment {@code appliedAmount} is computed as such at Payment
     * creation time. This service performs no currency conversion of its own.</p>
     *
     * @param stayId Stay identifier whose recorded financial amounts are aggregated
     * @return calculated Charge total, PAID-Payment applied total, and outstanding balance
     */
    @Transactional(readOnly = true)
    public StayBalance calculate(UUID stayId) {
        BigDecimal totalCharges = zeroIfNull(chargeRepository.sumAmountByStayId(stayId));
        BigDecimal totalPaidPayments = zeroIfNull(
                paymentRepository.sumAppliedAmountByStayIdAndStatus(stayId, PaymentStatus.PAID));
        return new StayBalance(
                totalCharges, totalPaidPayments, DepartureReadinessRules.outstanding(totalCharges, totalPaidPayments));
    }

    /**
     * Converts a nullable database aggregate result to a zero monetary amount.
     *
     * @param amount aggregate result that can be {@code null}
     * @return the supplied amount, or zero when no matching records exist
     */
    /**
     * Splits the Stay's ACTIVE Charges into ROOM Charges and additional (non-ROOM) Charges for presentation. The two
     * parts use the same ACTIVE filter as {@link #calculate}, so together they equal its Total Charges.
     *
     * @param stayId owning Stay identifier
     * @return the Room Charges and Additional Charges totals
     */
    @Transactional(readOnly = true)
    public StayChargeBreakdown chargeBreakdown(UUID stayId) {
        return new StayChargeBreakdown(
                zeroIfNull(chargeRepository.sumRoomAmountByStayId(stayId)),
                zeroIfNull(chargeRepository.sumAdditionalAmountByStayId(stayId)));
    }

    private BigDecimal zeroIfNull(BigDecimal amount) {
        return amount == null ? BigDecimal.ZERO : amount;
    }
}
