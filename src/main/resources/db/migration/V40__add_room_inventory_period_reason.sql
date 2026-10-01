-- Room Unavailability V1 Hardening: RoomInventoryPeriod already records the actual sellability
-- history behind MAINTENANCE/OUT_OF_ORDER; a reason column preserves WHY a period was unavailable
-- so restoring a Room to service does not lose that context. Existing rows have no reason recorded.
ALTER TABLE room_inventory_period
    ADD COLUMN reason TEXT;

ALTER TABLE room_inventory_period
    ADD CONSTRAINT room_inventory_period_reason_requires_unavailable
        CHECK (reason IS NULL OR unavailable_reason IS NOT NULL);
