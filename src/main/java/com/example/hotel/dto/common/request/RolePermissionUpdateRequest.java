package com.example.hotel.dto.common.request;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Carries the submitted Roles &amp; Permissions matrix. Every value is untrusted and validated by
 * the service against the built-in roles and the approved permission catalogue.
 */
public class RolePermissionUpdateRequest {

    private List<String> submittedRoles = new ArrayList<>();
    private Map<String, List<String>> grants = new LinkedHashMap<>();

    /**
     * Returns the role codes the form declared it was submitting.
     *
     * @return submitted role codes
     */
    public List<String> getSubmittedRoles() {
        return submittedRoles;
    }

    /**
     * Sets the role codes the form declared it was submitting.
     *
     * @param submittedRoles submitted role codes
     */
    public void setSubmittedRoles(List<String> submittedRoles) {
        this.submittedRoles = submittedRoles == null ? new ArrayList<>() : submittedRoles;
    }

    /**
     * Returns the checked permission codes per role code.
     *
     * @return granted permission codes keyed by role code
     */
    public Map<String, List<String>> getGrants() {
        return grants;
    }

    /**
     * Sets the checked permission codes per role code.
     *
     * @param grants granted permission codes keyed by role code
     */
    public void setGrants(Map<String, List<String>> grants) {
        this.grants = grants == null ? new LinkedHashMap<>() : grants;
    }
}
