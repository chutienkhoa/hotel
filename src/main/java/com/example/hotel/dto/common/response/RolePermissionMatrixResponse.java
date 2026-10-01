package com.example.hotel.dto.common.response;

import java.util.List;
import java.util.Map;

/**
 * Exposes the Roles &amp; Permissions matrix for the built-in roles.
 *
 * @param roles built-in role codes in display order
 * @param groups business-friendly permission groups
 */
public record RolePermissionMatrixResponse(List<String> roles, List<Group> groups) {

    /**
     * One business-friendly group of permissions.
     *
     * @param label group heading
     * @param items permissions in the group
     */
    public record Group(String label, List<Item> items) {}

    /**
     * One permission row.
     *
     * @param code permission code
     * @param label human-readable label
     * @param locked whether the row is a protected invariant that cannot be changed
     * @param granted whether each built-in role holds the permission, keyed by role code
     */
    public record Item(String code, String label, boolean locked, Map<String, Boolean> granted) {}
}
