INSERT INTO permission (
    id,
    code,
    name,
    created_at,
    updated_at
)
VALUES (
    '00000000-0000-0000-0000-000000000112',
    'MANAGE_ADDITIONAL_REVENUE',
    'Manage additional revenue',
    CURRENT_TIMESTAMP,
    CURRENT_TIMESTAMP
)
ON CONFLICT (code) DO NOTHING;

INSERT INTO role_permission (
    role_id,
    permission_id
)
SELECT
    r.id,
    p.id
FROM
    role r
CROSS JOIN
    permission p
WHERE
    r.code IN ('ADMIN', 'MANAGER')
    AND p.code = 'MANAGE_ADDITIONAL_REVENUE'
ON CONFLICT (role_id, permission_id) DO NOTHING;

CREATE TABLE additional_revenue_category (
    id UUID PRIMARY KEY,
    code VARCHAR(64) NOT NULL UNIQUE,
    name VARCHAR(128),
    description TEXT,
    active BOOLEAN NOT NULL DEFAULT TRUE,
    created_at TIMESTAMPTZ NOT NULL,
    created_by UUID,
    updated_at TIMESTAMPTZ NOT NULL,
    updated_by UUID
);

CREATE TABLE additional_revenue (
    id UUID PRIMARY KEY,
    category_id UUID NOT NULL REFERENCES additional_revenue_category(id),
    amount NUMERIC(19, 6) NOT NULL,
    currency CHAR(3) NOT NULL DEFAULT 'VND',
    revenue_date DATE NOT NULL,
    payment_method VARCHAR(32) NOT NULL,
    description TEXT,
    status VARCHAR(32) NOT NULL,
    void_reason TEXT,
    voided_at TIMESTAMPTZ,
    voided_by UUID REFERENCES app_user(id),
    created_at TIMESTAMPTZ NOT NULL,
    created_by UUID NOT NULL REFERENCES app_user(id),
    updated_at TIMESTAMPTZ NOT NULL,
    updated_by UUID NOT NULL REFERENCES app_user(id),
    CONSTRAINT additional_revenue_amount_positive CHECK (amount > 0),
    CONSTRAINT additional_revenue_currency_vnd CHECK (currency = 'VND'),
    CONSTRAINT additional_revenue_payment_method_supported CHECK (
        payment_method IN ('CASH', 'BANK_TRANSFER', 'CREDIT_CARD', 'OTHER')
    ),
    CONSTRAINT additional_revenue_status_supported CHECK (status IN ('RECORDED', 'VOIDED')),
    CONSTRAINT additional_revenue_void_consistency CHECK (
        (status = 'RECORDED' AND void_reason IS NULL AND voided_at IS NULL AND voided_by IS NULL)
        OR (status = 'VOIDED' AND void_reason IS NOT NULL AND voided_at IS NOT NULL AND voided_by IS NOT NULL)
    )
);

CREATE INDEX idx_additional_revenue_category_date ON additional_revenue(category_id, revenue_date, id);
CREATE INDEX idx_additional_revenue_status_date ON additional_revenue(status, revenue_date, id);

INSERT INTO additional_revenue_category (
    id,
    code,
    name,
    description,
    active,
    created_at,
    updated_at
)
VALUES
    ('00000000-0000-0000-0000-000000000401', 'ELECTRIC_CART_RENTAL', 'Electric Cart Rental', NULL, TRUE, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP),
    ('00000000-0000-0000-0000-000000000402', 'OTHER', 'Other', NULL, TRUE, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)
ON CONFLICT (code) DO NOTHING;
