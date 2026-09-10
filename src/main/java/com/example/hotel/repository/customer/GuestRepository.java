package com.example.hotel.repository.customer;

import com.example.hotel.entity.customer.Guest;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

/** Cung cấp thao tác lưu trữ cho khách. */
public interface GuestRepository extends JpaRepository<Guest, UUID> {}
