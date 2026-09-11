ALTER TABLE reservation
    ALTER COLUMN reservation_number TYPE VARCHAR(36)
    USING reservation_number::TEXT;

CREATE TABLE reservation_number_sequence (
    reservation_date DATE PRIMARY KEY,
    last_value INTEGER NOT NULL CHECK (last_value BETWEEN 1 AND 999999)
);
