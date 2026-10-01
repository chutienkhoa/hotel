-- Accompanying Guests of a Reservation: existing reusable Guest profiles associated with a Reservation in addition to
-- its Primary Guest (which stays in reservation.guest_id). A Guest appears at most once per Reservation.
-- "Primary Guest is not also an Accompanying Guest" is enforced by the domain/application layer, not by a trigger.
CREATE TABLE reservation_guest (
    id UUID PRIMARY KEY,
    reservation_id UUID NOT NULL REFERENCES reservation(id),
    guest_id UUID NOT NULL REFERENCES guest(id),
    created_at TIMESTAMPTZ NOT NULL,
    created_by UUID NOT NULL REFERENCES app_user(id),
    updated_at TIMESTAMPTZ NOT NULL,
    updated_by UUID NOT NULL REFERENCES app_user(id),
    CONSTRAINT reservation_guest_once UNIQUE (reservation_id, guest_id)
);

CREATE INDEX idx_reservation_guest_guest ON reservation_guest(guest_id);
