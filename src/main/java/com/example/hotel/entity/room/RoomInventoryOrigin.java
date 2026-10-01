package com.example.hotel.entity.room;

/** Identifies whether a Room inventory period was recorded live or installed as a foundation baseline. */
public enum RoomInventoryOrigin {
    /** Baseline created when the inventory history foundation was installed; asserts nothing about earlier dates. */
    BOOTSTRAP,
    /** Period recorded by the application as the Room or its inventory state actually changed. */
    RECORDED
}
