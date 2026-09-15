package com.example.hotel.dto.room.request;

import com.example.hotel.entity.room.RoomStatus;
import java.util.UUID;

/**
 * Captures the independent, optional Room Management list filters.
 */
public class RoomSearchCriteria {

    private String roomNumber;
    private UUID roomTypeId;
    private String floor;
    private RoomStatus status;

    /**
     * Returns the optional case-insensitive Room Number filter fragment.
     *
     * @return the supplied filter fragment, or {@code null} when absent
     */
    public String getRoomNumber() {
        return roomNumber;
    }

    /**
     * Sets the Room Number filter fragment supplied by the Room list form.
     *
     * @param roomNumber optional Room Number filter fragment
     */
    public void setRoomNumber(String roomNumber) {
        this.roomNumber = roomNumber;
    }

    /**
     * Returns the optional selected RoomType identifier filter.
     *
     * @return the selected RoomType identifier, or {@code null} when absent
     */
    public UUID getRoomTypeId() {
        return roomTypeId;
    }

    /**
     * Sets the RoomType identifier filter supplied by the Room list form.
     *
     * @param roomTypeId optional selected RoomType identifier
     */
    public void setRoomTypeId(UUID roomTypeId) {
        this.roomTypeId = roomTypeId;
    }

    /**
     * Returns the optional Floor filter value.
     *
     * @return the supplied Floor value, or {@code null} when absent
     */
    public String getFloor() {
        return floor;
    }

    /**
     * Sets the Floor filter value supplied by the Room list form.
     *
     * @param floor optional Floor filter value
     */
    public void setFloor(String floor) {
        this.floor = floor;
    }

    /**
     * Returns the optional selected Room status filter.
     *
     * @return the selected Room status, or {@code null} when absent
     */
    public RoomStatus getStatus() {
        return status;
    }

    /**
     * Sets the Room status filter supplied by the Room list form.
     *
     * @param status optional selected Room status
     */
    public void setStatus(RoomStatus status) {
        this.status = status;
    }

    /**
     * Trims the text filter fields and converts a blank value to an absent filter.
     */
    public void normalize() {
        roomNumber = normalizeField(roomNumber);
        floor = normalizeField(floor);
    }

    /**
     * Trims one filter value and converts blank input to an absent filter.
     *
     * @param value raw filter value supplied by the form
     * @return the trimmed value, or {@code null} when absent or blank
     */
    private static String normalizeField(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }
}
