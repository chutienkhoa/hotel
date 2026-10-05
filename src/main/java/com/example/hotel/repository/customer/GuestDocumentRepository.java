package com.example.hotel.repository.customer;

import com.example.hotel.entity.customer.GuestDocument;
import com.example.hotel.entity.customer.GuestDocumentType;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/** Provides persistence access to the sensitive document metadata owned by Guests. */
public interface GuestDocumentRepository extends JpaRepository<GuestDocument, UUID> {

    /**
     * Finds every document of a given type for a Guest in deterministic display order.
     *
     * @param guestId Guest identifier
     * @param documentType requested document type
     * @return matching documents ordered by creation time, then identifier
     */
    List<GuestDocument> findByGuestIdAndDocumentTypeOrderByCreatedAtAscIdAsc(
            UUID guestId, GuestDocumentType documentType);

    /**
     * Finds one document only when it belongs to the given Guest and matches the given type,
     * preventing a document identifier from resolving under an unrelated Guest.
     *
     * @param id document identifier
     * @param guestId Guest identifier the document must belong to
     * @param documentType document type the document must match
     * @return the document when the identifier, Guest, and type all match
     */
    Optional<GuestDocument> findByIdAndGuestIdAndDocumentType(UUID id, UUID guestId, GuestDocumentType documentType);

    /**
     * Determines whether a Guest owns at least one document of the given type.
     *
     * @param guestId Guest identifier
     * @param documentType requested document type
     * @return {@code true} when at least one matching document exists
     */
    boolean existsByGuestIdAndDocumentType(UUID guestId, GuestDocumentType documentType);

    /**
     * Lists the owning Guest and the identifier of every document of a given type, oldest first, without loading the
     * documents or their Guests. Used to find each Guest's first passport image in a single query.
     *
     * @param documentType requested document type
     * @return rows of {@code [guestId, documentId]} ordered by creation time, then identifier
     */
    @Query("SELECT d.guest.id, d.id FROM GuestDocument d WHERE d.documentType = :documentType "
            + "ORDER BY d.createdAt ASC, d.id ASC")
    List<Object[]> findGuestAndDocumentIdsByDocumentType(@Param("documentType") GuestDocumentType documentType);
}
