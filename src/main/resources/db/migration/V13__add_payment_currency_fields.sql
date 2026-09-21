ALTER TABLE payment
    ADD COLUMN currency VARCHAR(3),
    ADD COLUMN exchange_rate NUMERIC(19, 6),
    ADD COLUMN applied_amount NUMERIC(19, 6);

-- Before this migration, Payment.amount was implicitly interpreted in the owning
-- Reservation/Folio currency (no conversion ever happened). Backfill every existing Payment
-- from its own Reservation's currency (payment -> stay -> reservation.currency) rather than
-- assuming VND, since Reservation currency has always been allowed to be VND or USD.
UPDATE payment p
SET currency = r.currency,
    applied_amount = p.amount,
    exchange_rate = NULL
FROM stay s
JOIN reservation r ON r.id = s.reservation_id
WHERE s.id = p.stay_id;

ALTER TABLE payment
    ALTER COLUMN currency SET NOT NULL,
    ALTER COLUMN applied_amount SET NOT NULL;

ALTER TABLE payment
    ADD CONSTRAINT payment_currency_supported CHECK (currency IN ('VND', 'USD')),
    ADD CONSTRAINT payment_applied_amount_positive CHECK (applied_amount > 0),
    ADD CONSTRAINT payment_exchange_rate_positive CHECK (exchange_rate IS NULL OR exchange_rate > 0);
