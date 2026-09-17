package com.example.hotel.repository.customer;

import com.example.hotel.entity.customer.GuestDocument;
import com.example.hotel.entity.customer.GuestDocumentType;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

/** Provides persistence access to the sensitive document metadata owned by Guests. */
public interface GuestDocumentRepository extends JpaRepository<GuestDocument, UUID> {

    /**
     * Finds one document of a given type for a Guest.
     *
     * @param guestId Guest identifier
     * @param documentType requested document type
     * @return the document when present
     */
    Optional<GuestDocument> findByGuestIdAndDocumentType(UUID guestId, GuestDocumentType documentType);
}
