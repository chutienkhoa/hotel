INSERT INTO permission (
    id,
    code,
    name,
    created_at,
    updated_at
) VALUES
    ('00000000-0000-0000-0000-000000000114', 'MANAGE_STAFF', 'Manage staff', now(), now()),
    ('00000000-0000-0000-0000-000000000115', 'MANAGE_ATTENDANCE', 'Manage attendance', now(), now())
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
    AND p.code IN ('MANAGE_STAFF', 'MANAGE_ATTENDANCE')
ON CONFLICT (role_id, permission_id) DO NOTHING;

CREATE SEQUENCE staff_code_sequence
    MINVALUE 1
    MAXVALUE 999999
    START WITH 1
    INCREMENT BY 1;

CREATE TABLE staff (
    id UUID PRIMARY KEY,
    staff_code VARCHAR(32) NOT NULL UNIQUE,
    first_name VARCHAR(100) NOT NULL,
    last_name VARCHAR(100) NOT NULL,
    phone VARCHAR(100),
    email VARCHAR(255),
    position VARCHAR(100),
    start_date DATE NOT NULL,
    active BOOLEAN NOT NULL DEFAULT TRUE,
    notes TEXT,
    created_at TIMESTAMPTZ NOT NULL,
    created_by UUID NOT NULL REFERENCES app_user(id),
    updated_at TIMESTAMPTZ NOT NULL,
    updated_by UUID NOT NULL REFERENCES app_user(id)
);

CREATE INDEX ix_staff_active ON staff(active);

CREATE TABLE daily_work_record (
    id UUID PRIMARY KEY,
    staff_id UUID NOT NULL REFERENCES staff(id),
    work_date DATE NOT NULL,
    start_time TIME NOT NULL,
    end_time TIME NOT NULL,
    notes TEXT,
    created_at TIMESTAMPTZ NOT NULL,
    created_by UUID NOT NULL REFERENCES app_user(id),
    updated_at TIMESTAMPTZ NOT NULL,
    updated_by UUID NOT NULL REFERENCES app_user(id),
    CONSTRAINT daily_work_record_start_before_end CHECK (start_time < end_time)
);

CREATE UNIQUE INDEX ux_daily_work_record_staff_date ON daily_work_record(staff_id, work_date);

CREATE INDEX ix_daily_work_record_work_date ON daily_work_record(work_date);
