package com.hotel.security;

import java.util.UUID;

/** Đại diện cho principal tối giản của người dùng đã được xác thực. */
public record CurrentUser(UUID id, String username) {}
