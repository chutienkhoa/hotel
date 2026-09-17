CREATE TABLE guest_document (
    id UUID PRIMARY KEY,
    guest_id UUID NOT NULL REFERENCES guest(id),
    document_type VARCHAR(32) NOT NULL,
    original_name VARCHAR(255) NOT NULL,
    content_type VARCHAR(64) NOT NULL,
    file_size BIGINT NOT NULL CHECK (file_size > 0),
    storage_key VARCHAR(255) NOT NULL UNIQUE,
    created_at TIMESTAMPTZ NOT NULL,
    created_by UUID NOT NULL REFERENCES app_user(id),
    updated_at TIMESTAMPTZ NOT NULL,
    updated_by UUID NOT NULL REFERENCES app_user(id),
    CONSTRAINT guest_document_type CHECK (document_type IN ('PASSPORT_IMAGE')),
    CONSTRAINT guest_document_one_per_type UNIQUE (guest_id, document_type)
);

CREATE INDEX idx_guest_document_guest ON guest_document(guest_id);
