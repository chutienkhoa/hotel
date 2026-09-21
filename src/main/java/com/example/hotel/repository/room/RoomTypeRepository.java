package com.example.hotel.repository.room;

import com.example.hotel.entity.room.RoomType;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

/** Provides read-only persistence access to RoomType records. */
public interface RoomTypeRepository extends JpaRepository<RoomType, UUID> {}
