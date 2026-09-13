package com.example.hotel.room;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.example.hotel.entity.room.Room;
import com.example.hotel.entity.room.RoomStatus;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/** Verifies the explicit occupied-to-dirty Room transition used only by check-out. */
class RoomCheckOutTransitionTest {

    /** Confirms check-out marks an occupied Room dirty without changing its profile or active flag. */
    @Test
    void shouldMarkOccupiedRoomDirtyWithoutChangingProfile() {
        UUID roomId = UUID.randomUUID();
        Room room = Room.create(roomId, "101", null, "1");
        room.occupy();

        room.markDirty();

        assertEquals(RoomStatus.DIRTY, room.getStatus());
        assertEquals(roomId, room.getId());
        assertEquals("101", room.getRoomNumber());
        assertEquals("1", room.getFloor());
        assertTrue(room.isActive());
    }

    /** Confirms a Room outside OCCUPIED cannot be marked dirty by check-out. */
    @Test
    void shouldRejectMarkingNonOccupiedRoomDirty() {
        Room room = Room.create(UUID.randomUUID(), "101", null, "1");

        assertThrows(IllegalStateException.class, room::markDirty);
        assertEquals(RoomStatus.AVAILABLE, room.getStatus());
    }
}
