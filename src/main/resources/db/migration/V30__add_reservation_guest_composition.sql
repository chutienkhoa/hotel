-- Reservation guest composition (party size = adult_count + child_count). Existing Reservations are backfilled
-- with the safe V1 default of 1 adult and 0 children by adding the columns NOT NULL with a default. The defaults
-- are kept so that direct SQL inserts (fixtures, seeders) remain valid; the application always supplies both
-- values explicitly for new Reservations.
ALTER TABLE reservation
    ADD COLUMN adult_count INTEGER NOT NULL DEFAULT 1,
    ADD COLUMN child_count INTEGER NOT NULL DEFAULT 0;

ALTER TABLE reservation
    ADD CONSTRAINT reservation_adult_count_min CHECK (adult_count >= 1),
    ADD CONSTRAINT reservation_child_count_min CHECK (child_count >= 0);
