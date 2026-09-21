-- Stay Extension V1: history of planned check-out extensions of a CHECKED_IN Stay. The original booking snapshot
-- (reservation_room) is never modified; reservation.check_out_date becomes the CURRENT planned departure.
-- stay_extension is one extension event; stay_extension_room is one accommodation/pricing snapshot per room lineage
-- (original reservation_room) with the room actually occupied at the time, linked to the ROOM charge it posted.
CREATE TABLE stay_extension (
    id UUID PRIMARY KEY,
    stay_id UUID NOT NULL REFERENCES stay(id),
    sequence_no INTEGER NOT NULL,
    previous_check_out_date DATE NOT NULL,
    new_check_out_date DATE NOT NULL,
    created_at TIMESTAMPTZ NOT NULL,
    created_by UUID NOT NULL REFERENCES app_user(id),
    updated_at TIMESTAMPTZ NOT NULL,
    updated_by UUID NOT NULL REFERENCES app_user(id),
    CONSTRAINT stay_extension_dates CHECK (new_check_out_date > previous_check_out_date),
    CONSTRAINT stay_extension_sequence_positive CHECK (sequence_no > 0),
    CONSTRAINT stay_extension_sequence_once UNIQUE (stay_id, sequence_no),
    CONSTRAINT stay_extension_base_date_once UNIQUE (stay_id, previous_check_out_date)
);

CREATE TABLE stay_extension_room (
    id UUID PRIMARY KEY,
    stay_extension_id UUID NOT NULL REFERENCES stay_extension(id),
    original_reservation_room_id UUID NOT NULL REFERENCES reservation_room(id),
    room_id UUID NOT NULL REFERENCES room(id),
    from_date DATE NOT NULL,
    to_date DATE NOT NULL,
    nightly_rate NUMERIC(19, 6) NOT NULL,
    amount NUMERIC(19, 6) NOT NULL,
    charge_id UUID NOT NULL REFERENCES charge(id),
    created_at TIMESTAMPTZ NOT NULL,
    created_by UUID NOT NULL REFERENCES app_user(id),
    updated_at TIMESTAMPTZ NOT NULL,
    updated_by UUID NOT NULL REFERENCES app_user(id),
    CONSTRAINT stay_extension_room_dates CHECK (to_date > from_date),
    CONSTRAINT stay_extension_room_amount_positive CHECK (amount > 0),
    CONSTRAINT stay_extension_room_nightly_rate_positive CHECK (nightly_rate > 0),
    CONSTRAINT stay_extension_room_lineage_once UNIQUE (stay_extension_id, original_reservation_room_id),
    CONSTRAINT stay_extension_room_charge_once UNIQUE (charge_id)
);

CREATE INDEX idx_stay_extension_room_lineage ON stay_extension_room(original_reservation_room_id);
CREATE INDEX idx_stay_extension_room_room ON stay_extension_room(room_id);
CREATE INDEX idx_stay_extension_room_dates ON stay_extension_room(from_date, to_date);
