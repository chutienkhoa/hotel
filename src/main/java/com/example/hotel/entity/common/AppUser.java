package com.example.hotel.entity.common;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.JoinTable;
import jakarta.persistence.ManyToMany;
import jakarta.persistence.Table;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;

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
     * @param role role cần gán
     */
    public void addRole(Role role) {
        roles.add(role);
    }

    /**
     * Replaces the user's role set with exactly one role, as User Management V1 manages a single
     * role per account.
     *
     * @param role the only role the user will hold after this operation
     */
    public void replaceRoles(Role role) {
        roles.clear();
        roles.add(role);
    }

    /**
     * Replaces the stored password hash. The caller must supply an already-encoded hash.
     *
     * @param newPasswordHash the encoded replacement password hash
     */
    public void resetPassword(String newPasswordHash) {
        this.passwordHash = newPasswordHash;
    }

    /**
     * Deactivates the account so it can no longer authenticate.
     *
     * @throws IllegalStateException if the account is already inactive
     */
    public void deactivate() {
        if (!active) {
            throw new IllegalStateException("User account cannot deactivate from its current state");
        }
        active = false;
    }

    /**
     * Reactivates the account so it can authenticate again.
     *
     * @throws IllegalStateException if the account is already active
     */
    public void activate() {
        if (active) {
            throw new IllegalStateException("User account cannot activate from its current state");
        }
        active = true;
    }
}
