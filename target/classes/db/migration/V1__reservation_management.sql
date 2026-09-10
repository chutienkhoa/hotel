CREATE TABLE app_user (
    id UUID PRIMARY KEY,
    username VARCHAR(100) NOT NULL UNIQUE,
    password_hash VARCHAR(255) NOT NULL,
    email VARCHAR(255),
    active BOOLEAN NOT NULL DEFAULT TRUE,
    created_at TIMESTAMPTZ NOT NULL,
    created_by UUID,
    updated_at TIMESTAMPTZ NOT NULL,
    updated_by UUID
);

CREATE TABLE role (
    id UUID PRIMARY KEY,
    code VARCHAR(64) NOT NULL UNIQUE,
    name VARCHAR(128) NOT NULL,
    created_at TIMESTAMPTZ NOT NULL,
    created_by UUID,
    updated_at TIMESTAMPTZ NOT NULL,
    updated_by UUID
);

CREATE TABLE permission (
    id UUID PRIMARY KEY,
    code VARCHAR(64) NOT NULL UNIQUE,
    name VARCHAR(128) NOT NULL,
    created_at TIMESTAMPTZ NOT NULL,
    created_by UUID,
    updated_at TIMESTAMPTZ NOT NULL,
    updated_by UUID
);

CREATE TABLE user_role (
    user_id UUID NOT NULL REFERENCES app_user(id),
    role_id UUID NOT NULL REFERENCES role(id),
    PRIMARY KEY (user_id, role_id)
);

CREATE TABLE role_permission (
    role_id UUID NOT NULL REFERENCES role(id),
    permission_id UUID NOT NULL REFERENCES permission(id),
    PRIMARY KEY (role_id, permission_id)
);

CREATE TABLE guest (
    id UUID PRIMARY KEY,
    guest_code VARCHAR(100) NOT NULL UNIQUE,
    first_name VARCHAR(100),
    last_name VARCHAR(100),
    email VARCHAR(255),
    phone VARCHAR(100),
    nationality VARCHAR(100),
    date_of_birth DATE,
    address TEXT,
    created_at TIMESTAMPTZ NOT NULL,
    created_by UUID,
    updated_at TIMESTAMPTZ NOT NULL,
    updated_by UUID
);

CREATE TABLE room_type (
    id UUID PRIMARY KEY,
    code VARCHAR(64) NOT NULL UNIQUE,
    name VARCHAR(128) NOT NULL,
    description TEXT,
    capacity INTEGER,
    base_price NUMERIC(19, 6),
    active BOOLEAN NOT NULL DEFAULT TRUE,
    created_at TIMESTAMPTZ NOT NULL,
    created_by UUID,
    updated_at TIMESTAMPTZ NOT NULL,
    updated_by UUID
);

CREATE TABLE room (
    id UUID PRIMARY KEY,
    room_number VARCHAR(64) NOT NULL UNIQUE,
    room_type_id UUID REFERENCES room_type(id),
    floor VARCHAR(32),
    status VARCHAR(32) NOT NULL,
    active BOOLEAN NOT NULL DEFAULT TRUE,
    created_at TIMESTAMPTZ NOT NULL,
    created_by UUID,
    updated_at TIMESTAMPTZ NOT NULL,
    updated_by UUID
);

CREATE TABLE reservation (
    id UUID PRIMARY KEY,
    reservation_number UUID NOT NULL UNIQUE,
    guest_id UUID NOT NULL REFERENCES guest(id),
    source VARCHAR(32) NOT NULL,
    external_booking_id VARCHAR(255),
    status VARCHAR(32) NOT NULL,
    reserved_at TIMESTAMPTZ NOT NULL,
    check_in_date DATE NOT NULL,
    check_out_date DATE NOT NULL,
    currency CHAR(3) NOT NULL,
    total_amount NUMERIC(19, 6) NOT NULL,
    notes TEXT,
    created_at TIMESTAMPTZ NOT NULL,
    created_by UUID NOT NULL REFERENCES app_user(id),
    updated_at TIMESTAMPTZ NOT NULL,
    updated_by UUID NOT NULL REFERENCES app_user(id),
    CONSTRAINT reservation_dates CHECK (check_out_date > check_in_date),
    CONSTRAINT reservation_source_external UNIQUE (source, external_booking_id)
);

CREATE TABLE reservation_room (
    id UUID PRIMARY KEY,
    reservation_id UUID NOT NULL REFERENCES reservation(id),
    room_id UUID NOT NULL REFERENCES room(id),
    check_in_date DATE NOT NULL,
    check_out_date DATE NOT NULL,
    nightly_rate NUMERIC(19, 6) NOT NULL CHECK (nightly_rate > 0),
    total_amount NUMERIC(19, 6) NOT NULL,
    created_at TIMESTAMPTZ NOT NULL,
    created_by UUID NOT NULL REFERENCES app_user(id),
    updated_at TIMESTAMPTZ NOT NULL,
    updated_by UUID NOT NULL REFERENCES app_user(id),
    CONSTRAINT reservation_room_dates CHECK (check_out_date > check_in_date),
    CONSTRAINT reservation_room_once UNIQUE (reservation_id, room_id)
);

CREATE TABLE stay (
    id UUID PRIMARY KEY,
    reservation_id UUID NOT NULL UNIQUE REFERENCES reservation(id),
    status VARCHAR(32),
    actual_check_in_at TIMESTAMPTZ NOT NULL,
    actual_check_out_at TIMESTAMPTZ,
    notes TEXT,
    created_at TIMESTAMPTZ NOT NULL,
    created_by UUID NOT NULL REFERENCES app_user(id),
    updated_at TIMESTAMPTZ NOT NULL,
    updated_by UUID NOT NULL REFERENCES app_user(id)
);

CREATE TABLE audit_log (
    id UUID PRIMARY KEY,
    user_id UUID NOT NULL REFERENCES app_user(id),
    action VARCHAR(64) NOT NULL,
    entity_type VARCHAR(64) NOT NULL,
    entity_id UUID NOT NULL,
    old_value TEXT,
    new_value TEXT,
    ip_address VARCHAR(64),
    created_at TIMESTAMPTZ NOT NULL
);

CREATE INDEX idx_reservation_guest ON reservation(guest_id);
CREATE INDEX idx_reservation_dates ON reservation(check_in_date, check_out_date);
CREATE INDEX idx_reservation_external ON reservation(source, external_booking_id);
CREATE INDEX idx_reservation_room_dates ON reservation_room(room_id, check_in_date, check_out_date);
CREATE INDEX idx_audit_entity ON audit_log(entity_type, entity_id);
CREATE INDEX idx_audit_user_time ON audit_log(user_id, created_at);

INSERT INTO permission (
    id,
    code,
    name,
    created_at,
    updated_at
) VALUES
    ('00000000-0000-0000-0000-000000000101', 'MANAGE_BOOKING', 'Manage booking', now(), now()),
    ('00000000-0000-0000-0000-000000000102', 'CHECK_IN', 'Check in', now(), now()),
    ('00000000-0000-0000-0000-000000000103', 'MANAGE_USER', 'Manage user', now(), now()),
    ('00000000-0000-0000-0000-000000000104', 'MANAGE_ROOM', 'Manage room', now(), now()),
    ('00000000-0000-0000-0000-000000000105', 'MANAGE_PAYMENT', 'Manage payment', now(), now()),
    ('00000000-0000-0000-0000-000000000106', 'MANAGE_EXPENSE', 'Manage expense', now(), now()),
    ('00000000-0000-0000-0000-000000000107', 'VIEW_REPORT', 'View report', now(), now()),
    ('00000000-0000-0000-0000-000000000108', 'VIEW_BOOKING', 'View booking', now(), now()),
    ('00000000-0000-0000-0000-000000000109', 'CHECK_OUT', 'Check out', now(), now()),
    ('00000000-0000-0000-0000-000000000110', 'DELETE_RESERVATION', 'Delete reservation', now(), now());

INSERT INTO role (
    id,
    code,
    name,
    created_at,
    updated_at
) VALUES
    ('00000000-0000-0000-0000-000000000201', 'ADMIN', 'Administrator', now(), now()),
    ('00000000-0000-0000-0000-000000000202', 'MANAGER', 'Manager', now(), now()),
    ('00000000-0000-0000-0000-000000000203', 'STAFF', 'Staff', now(), now());

INSERT INTO role_permission (
    role_id,
    permission_id
) VALUES
    ('00000000-0000-0000-0000-000000000201', '00000000-0000-0000-0000-000000000101'),
    ('00000000-0000-0000-0000-000000000201', '00000000-0000-0000-0000-000000000102'),
    ('00000000-0000-0000-0000-000000000201', '00000000-0000-0000-0000-000000000103'),
    ('00000000-0000-0000-0000-000000000201', '00000000-0000-0000-0000-000000000104'),
    ('00000000-0000-0000-0000-000000000201', '00000000-0000-0000-0000-000000000105'),
    ('00000000-0000-0000-0000-000000000201', '00000000-0000-0000-0000-000000000106'),
    ('00000000-0000-0000-0000-000000000202', '00000000-0000-0000-0000-000000000101'),
    ('00000000-0000-0000-0000-000000000202', '00000000-0000-0000-0000-000000000104'),
    ('00000000-0000-0000-0000-000000000202', '00000000-0000-0000-0000-000000000105'),
    ('00000000-0000-0000-0000-000000000202', '00000000-0000-0000-0000-000000000107'),
    ('00000000-0000-0000-0000-000000000203', '00000000-0000-0000-0000-000000000108'),
    ('00000000-0000-0000-0000-000000000203', '00000000-0000-0000-0000-000000000102'),
    ('00000000-0000-0000-0000-000000000203', '00000000-0000-0000-0000-000000000109');
