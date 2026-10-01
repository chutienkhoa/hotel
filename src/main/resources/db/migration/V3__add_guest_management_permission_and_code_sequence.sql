CREATE SEQUENCE guest_code_sequence
    MINVALUE 1
    MAXVALUE 999999
    START WITH 1
    INCREMENT BY 1;

INSERT INTO permission (
    id,
    code,
    name,
    created_at,
    updated_at
) VALUES (
    '00000000-0000-0000-0000-000000000111',
    'MANAGE_GUEST',
    'Manage guest',
    now(),
    now()
);

INSERT INTO role_permission (
    role_id,
    permission_id
)
SELECT r.id,
       p.id
FROM role r
CROSS JOIN permission p
WHERE r.code IN ('ADMIN', 'MANAGER')
  AND p.code = 'MANAGE_GUEST'
ON CONFLICT (role_id, permission_id) DO NOTHING;
