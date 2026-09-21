CREATE TABLE charge (
    id UUID PRIMARY KEY,
    stay_id UUID NOT NULL REFERENCES stay(id),
    type VARCHAR(32) NOT NULL,
    description TEXT,
    quantity NUMERIC(19, 6),
    unit_price NUMERIC(19, 6),
    amount NUMERIC(19, 6) NOT NULL,
    charged_at TIMESTAMPTZ NOT NULL,
    created_at TIMESTAMPTZ NOT NULL,
    created_by UUID NOT NULL REFERENCES app_user(id),
    updated_at TIMESTAMPTZ NOT NULL,
    updated_by UUID NOT NULL REFERENCES app_user(id),
    CONSTRAINT charge_amount_positive CHECK (amount > 0),
    CONSTRAINT charge_quantity_unit_price_pair CHECK (
        (quantity IS NULL AND unit_price IS NULL)
        OR (quantity IS NOT NULL AND unit_price IS NOT NULL)
    ),
    CONSTRAINT charge_quantity_positive CHECK (quantity IS NULL OR quantity > 0),
    CONSTRAINT charge_unit_price_non_negative CHECK (unit_price IS NULL OR unit_price >= 0),
    CONSTRAINT charge_type_supported CHECK (
        type IN ('ROOM', 'BREAKFAST', 'EXTRA_BED', 'LAUNDRY', 'MINIBAR', 'SERVICE', 'OTHER')
    )
);

CREATE INDEX idx_charge_stay_charged_at ON charge(stay_id, charged_at, id);
