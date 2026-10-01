CREATE TABLE room_inventory_period (
    id UUID PRIMARY KEY,
    room_id UUID NOT NULL REFERENCES room(id),
    room_type_id UUID NOT NULL REFERENCES room_type(id),
    unavailable_reason VARCHAR(32),
    origin VARCHAR(16) NOT NULL,
    effective_from TIMESTAMPTZ NOT NULL,
    effective_to TIMESTAMPTZ,
    created_at TIMESTAMPTZ NOT NULL,
    created_by UUID REFERENCES app_user(id),
    updated_at TIMESTAMPTZ NOT NULL,
    updated_by UUID REFERENCES app_user(id),
    CONSTRAINT room_inventory_period_interval CHECK (effective_to IS NULL OR effective_to > effective_from),
    CONSTRAINT room_inventory_period_reason CHECK (
        unavailable_reason IS NULL OR unavailable_reason IN ('MAINTENANCE', 'OUT_OF_ORDER')),
    CONSTRAINT room_inventory_period_origin CHECK (origin IN ('BOOTSTRAP', 'RECORDED')),
    CONSTRAINT ux_room_inventory_period_room_from UNIQUE (room_id, effective_from)
);

CREATE UNIQUE INDEX ux_room_inventory_period_open_room
    ON room_inventory_period (room_id)
    WHERE effective_to IS NULL;

-- Foundation baseline: one open period per existing Room describing its state AT INSTALLATION only.
-- effective_from is the install instant, deliberately not room.created_at; nothing earlier is claimed.
-- Audit users are NULL because these rows are system-created.
INSERT INTO room_inventory_period (
    id,
    room_id,
    room_type_id,
    unavailable_reason,
    origin,
    effective_from,
    effective_to,
    created_at,
    created_by,
    updated_at,
    updated_by
)
SELECT
    gen_random_uuid(),
    r.id,
    r.room_type_id,
    CASE r.status
        WHEN 'MAINTENANCE' THEN 'MAINTENANCE'
        WHEN 'OUT_OF_ORDER' THEN 'OUT_OF_ORDER'
        ELSE NULL
    END,
    'BOOTSTRAP',
    CURRENT_TIMESTAMP,
    NULL,
    CURRENT_TIMESTAMP,
    NULL,
    CURRENT_TIMESTAMP,
    NULL
FROM room r;
