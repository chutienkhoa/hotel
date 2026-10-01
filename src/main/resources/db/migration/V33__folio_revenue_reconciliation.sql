-- Folio <-> Revenue reconciliation V1 (additive; no historical row is modified except by the safe, unambiguous
-- backfill below).
--
-- 1. charge.source_reservation_room_id: deterministic link from an ORIGINAL check-in ROOM charge to the
--    ReservationRoom it was created from. Stay Extension ROOM charges keep using stay_extension_room.charge_id and
--    never set this column. One ReservationRoom can produce at most one original ROOM charge.
ALTER TABLE charge
    ADD COLUMN source_reservation_room_id UUID REFERENCES reservation_room(id);

ALTER TABLE charge
    ADD CONSTRAINT charge_source_reservation_room_only_room CHECK (
        source_reservation_room_id IS NULL OR type = 'ROOM'
    );

CREATE UNIQUE INDEX ux_charge_source_reservation_room
    ON charge (source_reservation_room_id)
    WHERE source_reservation_room_id IS NOT NULL;

-- Legacy backfill. A check-in ROOM charge was created with description 'Room <booked room number>' and the
-- ReservationRoom total as amount. A charge is linked ONLY when exactly one ReservationRoom of its Stay's Reservation
-- matches (description and amount) AND that ReservationRoom matches exactly one charge. Extension charges are
-- excluded. Anything ambiguous or unmatched stays NULL and is reported by the reconciliation service.
WITH candidates AS (
    SELECT c.id AS charge_id, rr.id AS reservation_room_id
    FROM charge c
    JOIN stay s ON s.id = c.stay_id
    JOIN reservation_room rr ON rr.reservation_id = s.reservation_id
    JOIN room ro ON ro.id = rr.room_id
    WHERE c.type = 'ROOM'
      AND c.description = 'Room ' || ro.room_number
      AND c.amount = rr.total_amount
      AND NOT EXISTS (SELECT 1 FROM stay_extension_room l WHERE l.charge_id = c.id)
),
per_charge AS (
    SELECT charge_id, COUNT(*) AS matches FROM candidates GROUP BY charge_id
),
per_reservation_room AS (
    SELECT reservation_room_id, COUNT(*) AS matches FROM candidates GROUP BY reservation_room_id
)
UPDATE charge
SET source_reservation_room_id = candidates.reservation_room_id
FROM candidates
JOIN per_charge ON per_charge.charge_id = candidates.charge_id AND per_charge.matches = 1
JOIN per_reservation_room ON per_reservation_room.reservation_room_id = candidates.reservation_room_id
    AND per_reservation_room.matches = 1
WHERE charge.id = candidates.charge_id;

-- 2. additional_revenue.charge_id: a guest service revenue row that originates from a folio Charge. One Charge maps to
--    at most one revenue row and one revenue row to at most one Charge. Standalone revenue keeps charge_id NULL.
--    payment_method becomes nullable ONLY for Charge-linked rows: posting a Charge is not a payment event, so no
--    payment method is fabricated.
ALTER TABLE additional_revenue
    ADD COLUMN charge_id UUID REFERENCES charge(id),
    ALTER COLUMN payment_method DROP NOT NULL;

ALTER TABLE additional_revenue
    ADD CONSTRAINT additional_revenue_charge_once UNIQUE (charge_id),
    ADD CONSTRAINT additional_revenue_payment_method_or_charge CHECK (
        payment_method IS NOT NULL OR charge_id IS NOT NULL
    );

-- 3. Deterministic system categories for guest service Charges (code is the stable identity, never the display name).
INSERT INTO additional_revenue_category (id, code, name, description, active, created_at, updated_at)
VALUES
    ('00000000-0000-0000-0000-000000000411', 'GUEST_BREAKFAST', 'Guest Breakfast', 'Created from guest folio charges', TRUE, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP),
    ('00000000-0000-0000-0000-000000000412', 'GUEST_EXTRA_BED', 'Guest Extra Bed', 'Created from guest folio charges', TRUE, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP),
    ('00000000-0000-0000-0000-000000000413', 'GUEST_LAUNDRY', 'Guest Laundry', 'Created from guest folio charges', TRUE, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP),
    ('00000000-0000-0000-0000-000000000414', 'GUEST_MINIBAR', 'Guest Minibar', 'Created from guest folio charges', TRUE, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP),
    ('00000000-0000-0000-0000-000000000415', 'GUEST_SERVICE', 'Guest Service', 'Created from guest folio charges', TRUE, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP),
    ('00000000-0000-0000-0000-000000000416', 'GUEST_OTHER', 'Guest Other', 'Created from guest folio charges', TRUE, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)
ON CONFLICT (code) DO NOTHING;
