package com.example.hotel.common;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.ui.ConcurrentModel;

/** Verifies safe page parsing, paginator window, encoded link bases, and out-of-range redirects. */
class PaginationSupportTest {

    /** Confirms missing, malformed and negative pages normalize to page 0. */
    @Test
    void shouldParsePageSafely() {
        assertEquals(0, PaginationSupport.parsePage(null));
        assertEquals(0, PaginationSupport.parsePage("abc"));
        assertEquals(0, PaginationSupport.parsePage("-4"));
        assertEquals(0, PaginationSupport.parsePage("99999999999999999999"));
        assertEquals(3, PaginationSupport.parsePage(" 3 "));
    }

    /** Confirms link bases are encoded, keep filters and sort, and omit blank filters and the page. */
    @Test
    void shouldBuildEncodedLinkBasesPreservingFilterAndSort() {
        ConcurrentModel model = new ConcurrentModel();
        Map<String, String> filters = new LinkedHashMap<>();
        filters.put("guest", "A&B c+d");
        filters.put("blank", " ");
        filters.put("status", "CONFIRMED");

        PaginationSupport.populate(model, new PageImpl<>(List.of(), PageRequest.of(4, 10), 100), "/reservations",
                filters, "checkInDate", "desc");

        assertEquals("/reservations?guest=A%26B+c%2Bd&status=CONFIRMED&", model.get("tableSortBase"));
        assertEquals("/reservations?guest=A%26B+c%2Bd&status=CONFIRMED&sort=checkInDate&dir=desc&", model.get("tablePageBase"));
        assertEquals(3, model.get("paginationStartPage"));
        assertEquals(5, model.get("paginationEndPage"));
    }

    /** Confirms no filters and no sort produce a bare route base. */
    @Test
    void shouldBuildBareBaseWithoutFilterOrSort() {
        ConcurrentModel model = new ConcurrentModel();

        PaginationSupport.populate(model, new PageImpl<>(List.of(), PageRequest.of(0, 10), 0), "/guests", Map.of(), null, null);

        assertEquals("/guests?", model.get("tablePageBase"));
        assertNull(model.get("tableSortKey"));
    }

    /** Confirms a page past the end redirects to the last page with filters and sort, but an empty set does not. */
    @Test
    void shouldRedirectOnlyWhenPastEndOfNonEmptyResults() {
        Map<String, String> filters = Map.of("nationality", "Viet Nam");

        assertEquals("redirect:/guests?nationality=Viet+Nam&sort=lastName&dir=asc&page=2",
                PaginationSupport.redirectWhenOutOfRange(
                        new PageImpl<>(List.of(), PageRequest.of(999, 10), 25), 999, "/guests", filters, "lastName", "asc"));
        assertNull(PaginationSupport.redirectWhenOutOfRange(
                new PageImpl<>(List.of(), PageRequest.of(5, 10), 0), 5, "/guests", filters, null, null));
        assertNull(PaginationSupport.redirectWhenOutOfRange(
                new PageImpl<>(List.of("x"), PageRequest.of(2, 10), 21), 2, "/guests", filters, null, null));
    }
}
