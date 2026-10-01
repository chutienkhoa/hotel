DO $$
DECLARE
    conflict_count INTEGER;
BEGIN
    SELECT COUNT(*) INTO conflict_count
    FROM (
        SELECT LOWER(username) AS normalized_username
        FROM app_user
        GROUP BY LOWER(username)
        HAVING COUNT(*) > 1
    ) duplicates;

    IF conflict_count > 0 THEN
        RAISE EXCEPTION 'Cannot add case-insensitive unique username index: % conflicting username group(s) exist in app_user. Resolve them manually before migrating.', conflict_count;
    END IF;
END $$;

CREATE UNIQUE INDEX ux_app_user_username_lower ON app_user (LOWER(username));

ALTER TABLE staff ADD COLUMN app_user_id UUID;

ALTER TABLE staff ADD CONSTRAINT fk_staff_app_user FOREIGN KEY (app_user_id) REFERENCES app_user(id);

ALTER TABLE staff ADD CONSTRAINT uq_staff_app_user UNIQUE (app_user_id);
