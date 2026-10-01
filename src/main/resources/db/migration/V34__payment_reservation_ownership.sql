-- Deposit / Prepayment V1: every Payment belongs permanently to exactly one Reservation. Money received before
-- check-in (a prepayment) has stay_id NULL; at check-in the SAME row receives the new Stay's id. No Payment row is
-- ever copied and no financial column changes.
ALTER TABLE payment
    ADD COLUMN reservation_id UUID REFERENCES reservation(id);

-- Lossless, deterministic backfill: every existing Payment already has a Stay, and stay.reservation_id is its owner.
UPDATE payment p
SET reservation_id = s.reservation_id
FROM stay s
WHERE s.id = p.stay_id;

ALTER TABLE payment
    ALTER COLUMN reservation_id SET NOT NULL,
    ALTER COLUMN stay_id DROP NOT NULL;

-- A Payment's Stay must belong to the Payment's Reservation. A composite FK enforces it in the database without a
-- trigger; with stay_id NULL (a prepayment) the constraint does not apply (MATCH SIMPLE).
ALTER TABLE stay
    ADD CONSTRAINT ux_stay_id_reservation UNIQUE (id, reservation_id);

ALTER TABLE payment
    ADD CONSTRAINT fk_payment_stay_reservation
        FOREIGN KEY (stay_id, reservation_id) REFERENCES stay(id, reservation_id);

-- A prepayment (no Stay yet) is money already received: it is PAID, or REFUNDED after a whole refund. It is never
-- PENDING or FAILED.
ALTER TABLE payment
    ADD CONSTRAINT payment_prepayment_paid_or_refunded CHECK (
        stay_id IS NOT NULL OR status IN ('PAID', 'REFUNDED')
    );

CREATE INDEX idx_payment_reservation ON payment (reservation_id, created_at, id);
