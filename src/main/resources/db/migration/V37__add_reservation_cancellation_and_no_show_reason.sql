-- Cancellation Reason + No-show Reason V1: a structured reason (code + optional detail) is now required to
-- cancel a CONFIRMED reservation, and a required free-text operational reason is now required to mark one
-- NO_SHOW. All three columns are nullable because historical CANCELLED/NO_SHOW reservations were recorded
-- before this feature existed and must not be backfilled with an invented reason.
ALTER TABLE reservation
    ADD COLUMN cancellation_reason_code VARCHAR(32),
    ADD COLUMN cancellation_reason_detail TEXT,
    ADD COLUMN no_show_reason TEXT,
    ADD CONSTRAINT reservation_cancellation_other_requires_detail
        CHECK (cancellation_reason_code IS DISTINCT FROM 'OTHER' OR cancellation_reason_detail IS NOT NULL);
