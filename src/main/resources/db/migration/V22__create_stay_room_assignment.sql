CREATE TABLE stay_room_assignment (
    id UUID PRIMARY KEY,
    stay_id UUID NOT NULL REFERENCES stay(id),
    room_id UUID NOT NULL REFERENCES room(id),
    original_reservation_room_id UUID NOT NULL REFERENCES reservation_room(id),
    assigned_from TIMESTAMPTZ NOT NULL,
    assigned_to TIMESTAMPTZ,
    reason VARCHAR(32),
    notes TEXT,
    created_at TIMESTAMPTZ NOT NULL,
    created_by UUID NOT NULL REFERENCES app_user(id),
    updated_at TIMESTAMPTZ NOT NULL,
    updated_by UUID NOT NULL REFERENCES app_user(id),
    CONSTRAINT stay_room_assignment_interval CHECK (assigned_to IS NULL OR assigned_to > assigned_from),
    CONSTRAINT stay_room_assignment_other_requires_notes CHECK (reason IS DISTINCT FROM 'OTHER' OR notes IS NOT NULL)
);

CREATE UNIQUE INDEX ux_stay_room_assignment_open_room
    ON stay_room_assignment (room_id)
    WHERE assigned_to IS NULL;

CREATE UNIQUE INDEX ux_stay_room_assignment_open_lineage
    ON stay_room_assignment (stay_id, original_reservation_room_id)
    WHERE assigned_to IS NULL;

CREATE INDEX ix_stay_room_assignment_stay
    ON stay_room_assignment (stay_id);

CREATE INDEX ix_stay_room_assignment_lineage
    ON stay_room_assignment (original_reservation_room_id);

INSERT INTO stay_room_assignment (
    id,
    stay_id,
    room_id,
    original_reservation_room_id,
    assigned_from,
    assigned_to,
    reason,
    notes,
    created_at,
    created_by,
    updated_at,
    updated_by
)
SELECT
    gen_random_uuid(),
    s.id,
    rr.room_id,
    rr.id,
    s.actual_check_in_at,
    NULL,
    NULL,
    NULL,
    s.created_at,
    s.created_by,
    s.updated_at,
    s.updated_by
FROM stay s
INNER JOIN reservation_room rr
        ON rr.reservation_id = s.reservation_id
WHERE s.status = 'CHECKED_IN';
