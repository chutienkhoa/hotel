-- EXTEND_STAY: permission to extend the planned departure of an active CHECKED_IN stay through the Stay Extension
-- operation (normal or overdue). It is the ONLY authorization of Stay Extension; MANAGE_BOOKING no longer authorizes it.
-- V1 default: the three built-in roles receive it explicitly (ADMIN, MANAGER and STAFF, the front-desk operator).
-- Grants are NOT inferred from MANAGE_BOOKING and custom grants are not touched.
INSERT INTO permission (
    id,
    code,
    name,
    created_at,
    updated_at
) VALUES (
    '00000000-0000-0000-0000-000000000117',
    'EXTEND_STAY',
    'Extend stay',
    now(),
    now()
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
    r.code IN ('ADMIN', 'MANAGER', 'STAFF')
    AND p.code = 'EXTEND_STAY'
ON CONFLICT (role_id, permission_id) DO NOTHING;
