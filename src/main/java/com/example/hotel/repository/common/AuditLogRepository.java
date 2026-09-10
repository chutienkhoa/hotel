package com.example.hotel.repository.common;

import com.example.hotel.entity.common.AuditLog;
import java.util.*;
import org.springframework.data.jpa.repository.JpaRepository;

/** Cung cấp thao tác lưu trữ cho các bản ghi audit. */
public interface AuditLogRepository extends JpaRepository<AuditLog, UUID> {}
