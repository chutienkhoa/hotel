ALTER TABLE payment
    ADD COLUMN refund_reason TEXT;

ALTER TABLE payment
    ADD CONSTRAINT payment_refund_reason_required_when_refunded
    CHECK (status <> 'REFUNDED' OR (refund_reason IS NOT NULL AND btrim(refund_reason) <> ''));
