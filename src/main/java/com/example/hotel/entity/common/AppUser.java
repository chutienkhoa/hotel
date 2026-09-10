package com.example.hotel.entity.common;

import jakarta.persistence.*;
import java.util.*;

/** Đại diện cho người dùng có thể xác thực và được phân quyền trong hệ thống. */
@Entity
@Table(name = "app_user")
public class AppUser extends AuditedEntity {
  @Id private UUID id;

  @Column(nullable = false, unique = true)
  private String username;

  @Column(name = "password_hash", nullable = false)
  private String passwordHash;

  private String email;

  @Column(nullable = false)
  private boolean active = true;

  @ManyToMany(fetch = FetchType.EAGER)
  @JoinTable(
      name = "user_role",
      joinColumns = @JoinColumn(name = "user_id"),
      inverseJoinColumns = @JoinColumn(name = "role_id"))
  private Set<Role> roles = new HashSet<>();

  /** Tạo thực thể rỗng cho JPA. */
  protected AppUser() {}

  /**
   * Tạo người dùng với thông tin xác thực đã được mã hóa.
   *
   * @param id định danh người dùng
   * @param username tên đăng nhập
   * @param passwordHash mật khẩu đã băm
   */
  public AppUser(UUID id, String username, String passwordHash) {
    this.id = id;
    this.username = username;
    this.passwordHash = passwordHash;
  }

  /**
   * Trả về định danh người dùng.
   *
   * @return định danh người dùng
   */
  public UUID getId() {
    return id;
  }

  /**
   * Trả về tên đăng nhập.
   *
   * @return tên đăng nhập
   */
  public String getUsername() {
    return username;
  }

  /**
   * Trả về mật khẩu đã băm để xác thực.
   *
   * @return mật khẩu đã băm
   */
  public String getPasswordHash() {
    return passwordHash;
  }

  /**
   * Kiểm tra người dùng có đang hoạt động không.
   *
   * @return {@code true} nếu người dùng hoạt động
   */
  public boolean isActive() {
    return active;
  }

  /**
   * Trả về các role được gán cho người dùng.
   *
   * @return tập role của người dùng
   */
  public Set<Role> getRoles() {
    return roles;
  }

  /**
   * Gán role cho người dùng.
   *
   * @param r role cần gán
   */
  public void addRole(Role r) {
    roles.add(r);
  }
}
