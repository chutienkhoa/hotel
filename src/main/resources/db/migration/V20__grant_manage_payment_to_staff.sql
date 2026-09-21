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
    r.code = 'STAFF'
    AND p.code = 'MANAGE_PAYMENT'
ON CONFLICT (role_id, permission_id) DO NOTHING;
