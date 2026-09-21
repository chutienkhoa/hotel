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
    AND p.code = 'CHECK_OUT'
ON CONFLICT (role_id, permission_id) DO NOTHING;
