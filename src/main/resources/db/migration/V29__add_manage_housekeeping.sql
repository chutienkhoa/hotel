INSERT INTO permission (
    id,
    code,
    name,
    created_at,
    updated_at
) VALUES (
    '00000000-0000-0000-0000-000000000116',
    'MANAGE_HOUSEKEEPING',
    'Manage housekeeping',
    now(),
    now()
)
ON CONFLICT (code) DO NOTHING;

-- ADMIN and MANAGER receive the permission by default. Any other role that already holds
-- MANAGE_ROOM keeps the cleaning transitions it could perform before this permission existed.
-- Existing role-permission rows are never removed or overwritten.
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
    p.code = 'MANAGE_HOUSEKEEPING'
    AND (
        r.code IN ('ADMIN', 'MANAGER')
        OR r.id IN (
            SELECT
                rp.role_id
            FROM
                role_permission rp
            JOIN
                permission mp ON mp.id = rp.permission_id
            WHERE
                mp.code = 'MANAGE_ROOM'
        )
    )
ON CONFLICT (role_id, permission_id) DO NOTHING;
