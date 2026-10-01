INSERT INTO permission (
    id,
    code,
    name,
    created_at,
    updated_at
) VALUES (
    '00000000-0000-0000-0000-000000000113',
    'CHANGE_ROOM',
    'Change room',
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
    AND p.code = 'CHANGE_ROOM'
ON CONFLICT (role_id, permission_id) DO NOTHING;
