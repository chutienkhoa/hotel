package com.example.hotel.controller.common;

/**
 * One breadcrumb entry. A parent or intermediate entry carries the application-relative path it links to; the current
 * page (always last) and an entry the current user may not open carry no link.
 *
 * @param label the localized text shown for the entry
 * @param href application-relative path, or {@code null} when the entry is not a link
 */
public record BreadcrumbItem(String label, String href) {}
