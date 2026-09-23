-- Financial Integrity Hardening V1: the approved V1 Reservation currency set is VND and USD only.
--
-- Before this migration ReservationService accepted any ISO 4217 code, while Payment has always been
-- restricted to VND/USD (V13 payment_currency_supported). A Reservation in any other currency could
-- therefore be created through the REST API, confirmed and checked in, but never settled: no Payment
-- could be recorded against it, so its Outstanding balance could never reach zero and its Stay could
-- never check out. This constraint closes that gap in the database, mirroring the existing
-- payment_currency_supported CHECK, so the invariant does not depend on the UI dropdown or on
-- application code alone.
--
-- Additive and non-destructive: no row is inserted, updated or deleted. Every Reservation created by
-- the application, by the demo data seeder and by every migration and integration test fixture in this
-- repository already uses 'VND' or 'USD', so the constraint validates cleanly against existing data.
-- If a deployment does hold a Reservation in another currency, this migration fails loudly and leaves
-- that row untouched rather than silently rewriting recorded financial history; resolving it is a
-- product decision, not a data fix this migration may make.
ALTER TABLE reservation
    ADD CONSTRAINT reservation_currency_supported CHECK (currency IN ('VND', 'USD'));
