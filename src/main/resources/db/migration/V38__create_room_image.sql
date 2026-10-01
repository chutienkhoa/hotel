-- Room Images V1: 0..10 privately stored photos per physical Room, independent from RoomType and from
-- Room.status. At most one PRIMARY image per Room is enforced here with a partial unique index (the same
-- idiom already used by ux_stay_room_assignment_open_room in V22); application logic keeps it populated
-- (first upload becomes primary; deleting the primary promotes the oldest remaining image). No display_order
-- column: V1 does not support explicit reordering. No binary content is stored in PostgreSQL.
CREATE TABLE room_image (
    id UUID PRIMARY KEY,
    room_id UUID NOT NULL REFERENCES room(id),
    storage_key VARCHAR(255) NOT NULL UNIQUE,
    original_filename VARCHAR(255) NOT NULL,
    content_type VARCHAR(64) NOT NULL,
    file_size BIGINT NOT NULL CHECK (file_size > 0),
    is_primary BOOLEAN NOT NULL DEFAULT FALSE,
    created_at TIMESTAMPTZ NOT NULL,
    created_by UUID NOT NULL REFERENCES app_user(id),
    updated_at TIMESTAMPTZ NOT NULL,
    updated_by UUID NOT NULL REFERENCES app_user(id)
);

CREATE INDEX idx_room_image_room ON room_image(room_id);

CREATE UNIQUE INDEX ux_room_image_primary_per_room
    ON room_image (room_id)
    WHERE is_primary;
