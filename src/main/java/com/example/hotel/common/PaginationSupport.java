package com.example.hotel.common;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import org.springframework.data.domain.Page;
import org.springframework.ui.Model;

/**
 * Small shared mechanics for the primary data-table screens: safe page parsing, the paginator
 * window, encoded link bases that preserve filter, sort and direction, and out-of-range page
 * redirects. Contains no domain rules.
 */
public final class PaginationSupport {

    private PaginationSupport() {}

    /**
     * Parses a requested zero-based page, treating missing, malformed or negative values as page 0.
     *
     * @param page raw request parameter
     * @return a non-negative page index
     */
    public static int parsePage(String page) {
        if (page == null) {
            return 0;
        }
        try {
            return Math.max(Integer.parseInt(page.trim()), 0);
        } catch (NumberFormatException exception) {
            return 0;
        }
    }

    /**
     * Adds paginator window bounds and encoded link bases to the model.
     *
     * @param model MVC model
     * @param page current page metadata
     * @param path list route, such as {@code /guests}
     * @param filters currently active filter parameters (unencoded, blank ones omitted)
     * @param sortKey validated active sort key, or {@code null}
     * @param sortDir validated active direction, or {@code null}
     */
    public static void populate(
            Model model, Page<?> page, String path, Map<String, String> filters, String sortKey, String sortDir) {
        int totalPages = page.getTotalPages();
        if (totalPages > 0) {
            int lastPage = totalPages - 1;
            int startPage = Math.max(0, Math.min(page.getNumber() - 1, lastPage - 2));
            model.addAttribute("paginationStartPage", startPage);
            model.addAttribute("paginationEndPage", Math.min(lastPage, startPage + 2));
        }
        String filterQuery = query(filters);
        String sortQuery = sortKey == null ? "" : "sort=" + encode(sortKey) + "&dir=" + encode(sortDir) + "&";
        model.addAttribute("tableSortBase", path + "?" + filterQuery);
        model.addAttribute("tablePageBase", path + "?" + filterQuery + sortQuery);
        model.addAttribute("tableSortKey", sortKey);
        model.addAttribute("tableSortDir", sortDir);
    }

    /**
     * Builds the redirect to the last valid page when the requested page is past the end of a
     * non-empty result set, preserving filters, sort and direction.
     *
     * @param page the page returned for the requested index
     * @param requestedPage the requested zero-based page
     * @param path list route
     * @param filters active filter parameters
     * @param sortKey validated active sort key, or {@code null}
     * @param sortDir validated active direction, or {@code null}
     * @return a {@code redirect:} view name, or {@code null} when no redirect is needed
     */
    public static String redirectWhenOutOfRange(
            Page<?> page, int requestedPage, String path, Map<String, String> filters, String sortKey, String sortDir) {
        if (page.getTotalPages() == 0 || requestedPage < page.getTotalPages()) {
            return null;
        }
        String sortQuery = sortKey == null ? "" : "sort=" + encode(sortKey) + "&dir=" + encode(sortDir) + "&";
        return "redirect:" + path + "?" + query(filters) + sortQuery + "page=" + (page.getTotalPages() - 1);
    }

    /**
     * Builds an encoded {@code name=value&} sequence (with a trailing separator) from filters.
     *
     * @param filters filter parameters
     * @return the encoded query prefix, or an empty string
     */
    private static String query(Map<String, String> filters) {
        StringBuilder query = new StringBuilder();
        filters.forEach((name, value) -> {
            if (value != null && !value.isBlank()) {
                query.append(encode(name)).append('=').append(encode(value)).append('&');
            }
        });
        return query.toString();
    }

    private static String encode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }
}
