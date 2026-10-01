package com.example.hotel.repository.room;

import com.example.hotel.entity.room.RoomImage;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

/** Provides persistence access to the privately stored Room image metadata. */
public interface RoomImageRepository extends JpaRepository<RoomImage, UUID> {

    /**
     * Finds every image owned by a Room in deterministic display order.
     *
     * @param roomId Room identifier
     * @return matching images ordered by creation time, then identifier
     */
    List<RoomImage> findByRoomIdOrderByCreatedAtAscIdAsc(UUID roomId);

    /**
     * Finds one image only when it belongs to the given Room, preventing an image identifier
     * from resolving under an unrelated Room.
     *
     * @param id image identifier
     * @param roomId Room identifier the image must belong to
     * @return the image when the identifier and Room both match
     */
    Optional<RoomImage> findByIdAndRoomId(UUID id, UUID roomId);

    /**
     * Counts the images currently owned by a Room.
     *
     * @param roomId Room identifier
     * @return the current image count
     */
    long countByRoomId(UUID roomId);

    /**
     * Finds the Room's current primary image, if any.
     *
     * @param roomId Room identifier
     * @return the primary image, when one exists
     */
    Optional<RoomImage> findByRoomIdAndPrimaryTrue(UUID roomId);
}
