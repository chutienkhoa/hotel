CREATE TABLE payment (
    id UUID PRIMARY KEY,
    stay_id UUID NOT NULL REFERENCES stay(id),
    amount NUMERIC(19, 6) NOT NULL,
    method VARCHAR(32) NOT NULL,
    status VARCHAR(32) NOT NULL,
    paid_at TIMESTAMPTZ,
    reference TEXT,
    created_at TIMESTAMPTZ NOT NULL,
    created_by UUID NOT NULL REFERENCES app_user(id),
    updated_at TIMESTAMPTZ NOT NULL,
    updated_by UUID NOT NULL REFERENCES app_user(id),
    CONSTRAINT payment_amount_positive CHECK (amount > 0),
    CONSTRAINT payment_method_supported CHECK (
        method IN ('CASH', 'CREDIT_CARD', 'BANK_TRANSFER', 'OTA', 'OTHER')
    ),
    CONSTRAINT payment_status_supported CHECK (
        status IN ('PENDING', 'PAID', 'FAILED', 'REFUNDED')
    )
);

CREATE INDEX idx_payment_stay_created_at ON payment(stay_id, created_at, id);
