-- OTA External Identity (approved decision): for a non-DIRECT Reservation the pair
-- (source, ota_booking_reference) is a permanently unique external booking identity. Two Reservations
-- may never claim the same OTA booking, and that identity is NOT released by a terminal state: an
-- existing CANCELLED, NO_SHOW or CHECKED_OUT Reservation still blocks re-using its reference, because a
-- genuinely new OTA booking always carries a new reference. Different sources are independent, so
-- AGODA + ABC123 and BOOKING_COM + ABC123 legitimately coexist.
--
-- Implemented as a PARTIAL UNIQUE INDEX rather than a table constraint because the rule applies only to
-- non-DIRECT rows: DIRECT Reservations never carry an OTA reference (the domain nulls it on construction)
-- and must stay outside this identity rule entirely. PostgreSQL cannot express a filtered uniqueness rule
-- as an ALTER TABLE ... ADD CONSTRAINT UNIQUE, so no pg_constraint entry is created; the index is still a
-- fully authoritative write barrier that serializes concurrent inserts of the same identity.
--
-- The reference is matched EXACTLY as stored. V1 stores a non-blank staff-entered reference verbatim
-- (see Reservation#correctOtaBookingReference and spec 73), so this migration deliberately adds no case
-- folding, trimming or punctuation normalization, which would silently change the accepted meaning of
-- existing data.
--
-- Additive and non-destructive: no row is inserted, updated or deleted, and the legacy
-- external_booking_id column and its own UNIQUE (source, external_booking_id) constraint from V1 are left
-- untouched. Every Reservation produced by the application, the demo seeder and every migration and test
-- fixture in this repository has a distinct (source, ota_booking_reference) pair for non-DIRECT rows, so
-- the index builds cleanly. If a deployment does hold duplicates, this migration fails loudly and leaves
-- the data untouched rather than deleting, merging or renaming historical bookings; resolving that is a
-- product decision, not a data fix this migration may make.
CREATE UNIQUE INDEX ux_reservation_ota_identity
    ON reservation (source, ota_booking_reference)
    WHERE source <> 'DIRECT'
        AND ota_booking_reference IS NOT NULL;
