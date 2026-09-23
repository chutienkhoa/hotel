-- Charge / Payment Void Correction V1 (additive; no historical financial value is changed).
--
-- Distinguishes VOID (the record itself was wrong; the money it represents never actually moved)
-- from REFUND (money actually received and later returned to the guest). The two stay unambiguous
-- in persisted data: REFUND keeps using the existing payment.refund_reason column and REFUNDED
-- status; VOID gets its own status and its own reason column on both tables.
--
-- 1. Charge lifecycle: ACTIVE / VOIDED. Existing rows become ACTIVE. void_reason is required exactly
--    when VOIDED. updated_at/updated_by (already NOT NULL on every Charge) remain the authoritative
--    void timestamp/actor, mirroring the existing Payment refund convention (V24): no separate
--    voided_at/voided_by column is added. The DEFAULT is kept permanently (not dropped after
--    backfill): the demo data seeder and several existing integration tests insert ROOM charges with
--    a raw SQL INSERT that does not list every column, exactly as they already do for other Charge
--    columns added after the original table, so the default keeps that pre-existing, unrelated code
--    working unchanged. The application layer (Charge.create/createOriginalRoomCharge) always sets
--    status explicitly regardless.
ALTER TABLE charge
    ADD COLUMN status VARCHAR(32) NOT NULL DEFAULT 'ACTIVE',
    ADD COLUMN void_reason TEXT;

ALTER TABLE charge
    ADD CONSTRAINT charge_status_supported CHECK (status IN ('ACTIVE', 'VOIDED')),
    ADD CONSTRAINT charge_void_reason_required_when_voided CHECK (
        status <> 'VOIDED' OR (void_reason IS NOT NULL AND btrim(void_reason) <> '')
    ),
    ADD CONSTRAINT charge_void_reason_only_when_voided CHECK (
        status = 'VOIDED' OR void_reason IS NULL
    );

CREATE INDEX idx_charge_stay_status ON charge(stay_id, status);

-- 2. Payment lifecycle: add VOIDED as a status distinct from REFUNDED. void_reason is a separate
--    column from the existing refund_reason so REFUND and VOID can never be confused in persisted
--    data; required exactly when VOIDED, mirroring V24's refund_reason_required_when_refunded.
ALTER TABLE payment
    ADD COLUMN void_reason TEXT;

ALTER TABLE payment
    DROP CONSTRAINT payment_status_supported;

ALTER TABLE payment
    ADD CONSTRAINT payment_status_supported CHECK (
        status IN ('PENDING', 'PAID', 'FAILED', 'REFUNDED', 'VOIDED')
    );

ALTER TABLE payment
    ADD CONSTRAINT payment_void_reason_required_when_voided CHECK (
        status <> 'VOIDED' OR (void_reason IS NOT NULL AND btrim(void_reason) <> '')
    ),
    ADD CONSTRAINT payment_void_reason_only_when_voided CHECK (
        status = 'VOIDED' OR void_reason IS NULL
    );

-- A prepayment (no Stay yet) is money already received: it is PAID, REFUNDED after a whole refund,
-- or VOIDED as an erroneous record before it was ever attached to a Stay. It is never PENDING or
-- FAILED. Widens the existing V34 constraint of the same name.
ALTER TABLE payment
    DROP CONSTRAINT payment_prepayment_paid_or_refunded;

ALTER TABLE payment
    ADD CONSTRAINT payment_prepayment_paid_or_refunded CHECK (
        stay_id IS NOT NULL OR status IN ('PAID', 'REFUNDED', 'VOIDED')
    );
