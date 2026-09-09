package com.hotel.security;

import com.hotel.common.AuditedEntity;
import jakarta.persistence.*;
import java.util.*;

/** Đại diện cho role tập hợp các permission của người dùng. */
@Entity
@Table(name = "role")
public class Role extends AuditedEntity {
  @Id private UUID id;

  @Column(nullable = false, unique = true)
  private String code;

  private String name;

  @ManyToMany(fetch = FetchType.EAGER)
  @JoinTable(
      name = "role_permission",
      joinColumns = @JoinColumn(name = "role_id"),
      inverseJoinColumns = @JoinColumn(name = "permission_id"))
  private Set<Permission> permissions = new HashSet<>();

  /** Tạo thực thể rỗng cho JPA. */
  protected Role() {}

  /**
   * Trả về định danh role.
   *
   * @return định danh role
   */
  public UUID getId() {
    return id;
  }

  /**
   * Trả về các permission thuộc role.
   *
   * @return tập permission của role
   */
  public Set<Permission> getPermissions() {
    return permissions;
  }
}
