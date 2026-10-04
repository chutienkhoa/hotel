package com.example.hotel.dto.booking.request;

import com.example.hotel.common.validation.RequiresNotesForOtherReason;
import com.example.hotel.entity.booking.RoomChangeReason;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.util.UUID;

/**
 * Dữ liệu request cho một lần đổi phòng sau khi check-in. {@code changedAt} và {@code changedBy}
 * không bao giờ được client cung cấp; chúng luôn được {@code RoomChangeService} gán server-side
 * từ Clock chính thức và người dùng đã xác thực.
 */
@RequiresNotesForOtherReason
public record RoomChangeRequest(
        @NotNull(message = "{validation.roomChange.targetRoom.required}") UUID targetRoomId,
        @NotNull(message = "{validation.roomChange.reason.required}") RoomChangeReason reason,
        @Size(max = 2000) String notes) {}
