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
     * Returns the role code, such as ADMIN, MANAGER, or STAFF.
     *
     * @return the role code
     */
    public String getCode() {
        return code;
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
