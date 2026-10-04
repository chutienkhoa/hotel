-- Guest identity (approved product decision): an optional structured ID / Passport Number on the Guest.
-- The concept is deliberately neutral (id_document_number) because the hotel serves both Vietnamese guests
-- identified by a national ID / CCCD and international guests identified by a passport. date_of_birth already
-- exists on guest (V1), so only this column is new.
--
-- Additive and non-destructive: the column is nullable, so every existing Guest keeps working unchanged and no
-- data migration is needed. It is intentionally NOT unique: legacy or unknown data and operational corrections
-- must not be blocked by an unvalidated uniqueness assumption. Passport images remain a separate document
-- concern (guest_document) and are not touched.
ALTER TABLE guest
    ADD COLUMN id_document_number VARCHAR(50);
