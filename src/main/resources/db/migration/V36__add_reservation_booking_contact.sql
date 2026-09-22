-- Booking Contact V1: an optional Reservation-level snapshot of the name/phone/email the hotel should contact
-- about this booking. It is independent from Primary Guest, Accompanying Guests, otaBookingReference and
-- externalBookingId. All three columns are nullable and no historical Reservation is backfilled, because the
-- historical Primary Guest is not known to be the actual booking contact.
ALTER TABLE reservation
    ADD COLUMN booking_contact_name VARCHAR(200),
    ADD COLUMN booking_contact_phone VARCHAR(100),
    ADD COLUMN booking_contact_email VARCHAR(255);
