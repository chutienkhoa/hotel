package com.example.hotel.dto.booking.request;

import com.example.hotel.common.validation.RequiresDetailForOtherCancellationReason;
import com.example.hotel.entity.booking.CancellationReasonCode;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/**
 * Carries the structured reason required by every cancellation of a CONFIRMED Reservation.
 *
 * @param cancellationReasonCode the required structured cancellation reason
 * @param cancellationReasonDetail optional free-text detail, required when {@code cancellationReasonCode} is
 *     {@link CancellationReasonCode#OTHER}
 */
@RequiresDetailForOtherCancellationReason
public record CancelReservationRequest(
        @NotNull(message = "{validation.reservation.cancel.reasonCode.required}")
                CancellationReasonCode cancellationReasonCode,
        @Size(max = 2000, message = "{validation.reservation.cancel.reasonDetail.max}")
                String cancellationReasonDetail) {}
