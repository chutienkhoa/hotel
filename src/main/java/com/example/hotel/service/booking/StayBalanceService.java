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
     * @param paymentRepository repository used to sum PAID Payment amounts
     */
    public StayBalanceService(
            ChargeRepository chargeRepository, PaymentRepository paymentRepository) {
        this.chargeRepository = chargeRepository;
        this.paymentRepository = paymentRepository;
    }

    /**
     * Calculates the outstanding balance for a Stay.
     *
     * @param stayId Stay identifier whose recorded financial amounts are aggregated
     * @return calculated Charge total, PAID-Payment total, and outstanding balance
     */
    @Transactional(readOnly = true)
    public StayBalance calculate(UUID stayId) {
        BigDecimal totalCharges = zeroIfNull(chargeRepository.sumAmountByStayId(stayId));
        BigDecimal totalPaidPayments = zeroIfNull(
                paymentRepository.sumAmountByStayIdAndStatus(stayId, PaymentStatus.PAID));
        return new StayBalance(
                totalCharges, totalPaidPayments, totalCharges.subtract(totalPaidPayments));
    }

    /**
     * Converts a nullable database aggregate result to a zero monetary amount.
     *
     * @param amount aggregate result that can be {@code null}
     * @return the supplied amount, or zero when no matching records exist
     */
    private BigDecimal zeroIfNull(BigDecimal amount) {
        return amount == null ? BigDecimal.ZERO : amount;
    }
}
