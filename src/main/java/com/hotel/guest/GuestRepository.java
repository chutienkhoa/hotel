package com.hotel.guest;

import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

/** Cung cấp thao tác lưu trữ cho khách. */
public interface GuestRepository extends JpaRepository<Guest, UUID> {}
