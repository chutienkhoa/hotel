package com.example.hotel.dto.booking.response;

/** Severity of one Arrival Readiness issue. */
public enum ArrivalIssueSeverity {
    /** Prevents check-in; the authoritative check-in operation rejects the same condition. */
    BLOCKER,
    /** Operational context that does not prevent check-in. */
    WARNING,
    /** Useful arrival context. */
    INFO
}
