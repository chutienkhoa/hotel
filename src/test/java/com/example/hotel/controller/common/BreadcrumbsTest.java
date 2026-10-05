package com.example.hotel.controller.common;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.example.hotel.common.i18n.UiMessages;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import java.util.function.Function;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.context.i18n.LocaleContextHolder;
import org.springframework.context.support.ResourceBundleMessageSource;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;

/** Verifies the workflow breadcrumb trails: Front Desk flows, context preservation, depth, links and permissions. */
class BreadcrumbsTest {

    private static final UUID ID = UUID.fromString("11111111-1111-1111-1111-111111111111");
    private static final String[] ALL = {"PERM_CHECK_IN", "PERM_CHECK_OUT", "PERM_VIEW_BOOKING", "PERM_MANAGE_GUEST"};

    private UiMessages messages;

    @BeforeEach
    void setUp() {
        ResourceBundleMessageSource source = new ResourceBundleMessageSource();
        source.setBasename("messages");
        source.setDefaultEncoding("UTF-8");
        source.setFallbackToSystemLocale(false);
        messages = new UiMessages(source);
        LocaleContextHolder.setLocale(Locale.ENGLISH);
    }

    @AfterEach
    void resetLocale() {
        LocaleContextHolder.resetLocaleContext();
    }

    private Breadcrumbs crumbs(String from, String... authorities) {
        return new Breadcrumbs(messages, LocaleContextHolder.getLocale(), new UsernamePasswordAuthenticationToken("u", null,
                java.util.Arrays.stream(authorities).map(SimpleGrantedAuthority::new).toList()), from);
    }

    private static String text(List<BreadcrumbItem> trail) {
        return String.join(" / ", trail.stream().map(BreadcrumbItem::label).toList());
    }

    private static List<String> links(List<BreadcrumbItem> trail) {
        return trail.stream().map(item -> item.href() == null ? "-" : item.href()).toList();
    }

    /** Confirms Reassign Room opened from Reservation Detail leads back to that Reservation, not to Front Desk. */
    @Test
    void shouldBuildTheReassignTrailForAReservationOpenedFromDetail() {
        Breadcrumbs c = crumbs(null, ALL);

        assertEquals("Reservations / Reservation R20261004-000001 / Reassign Room",
                text(c.reassignFromReservation(ID, "R20261004-000001")));
        assertEquals(List.of("/reservations", "/reservations/" + ID, "-"),
                links(c.reassignFromReservation(ID, "R20261004-000001")));
        // Without VIEW_BOOKING neither the list nor the Reservation can be opened, so both entries are plain text.
        assertEquals(List.of("-", "-", "-"), links(crumbs(null, "PERM_CHECK_IN").reassignFromReservation(ID, "R1")));
    }

    /** Confirms the Front Desk views, Check-in, Walk-in, OTA and Check-out flows follow the approved naming. */
    @Test
    void shouldBuildTheFrontDeskTrails() {
        Breadcrumbs c = crumbs(null, ALL);
        assertEquals("Front Desk / Arrivals", text(c.frontDeskView("arrivals")));
        assertEquals("Front Desk / In-house", text(c.frontDeskView("in-house")));
        assertEquals("Front Desk / Departures", text(c.frontDeskView("departures")));
        assertEquals("Front Desk / Check-in", text(c.checkInLanding()));
        assertEquals("Front Desk / Check-in / Find Reservation", text(c.checkInFind()));
        assertEquals("Front Desk / Check-in / Review", text(c.checkInReview()));
        assertEquals("Front Desk / Check-in / Review / Reassign Room", text(c.checkInReassign(ID)));
        assertEquals("Front Desk / Check-in / Walk-in", text(c.checkInWalkIn()));
        assertEquals("Front Desk / Check-in / Walk-in / Summary", text(c.checkInWalkInSummary()));
        assertEquals("Front Desk / Check-in / OTA Reservation", text(c.checkInOta()));
        assertEquals("Front Desk / Check-in / OTA Reservation / Summary", text(c.checkInOtaSummary()));
        assertEquals("Front Desk / Check-out", text(c.checkOutSearch()));
        assertEquals("Front Desk / Check-out / Review", text(c.checkOutReview()));
        assertEquals("Front Desk / Check-out / Complete", text(c.checkOutComplete()));
        assertEquals(List.of("/front-desk", "/check-in", "-"), links(c.checkInWalkIn()));
        assertEquals(List.of("/front-desk", "/check-in", "/check-in/walk-in", "-"), links(c.checkInWalkInSummary()));
        assertEquals("/front-desk?view=arrivals", crumbs("arrivals", ALL).checkInReview().get(1).href());
    }

    /** Confirms the same destination gets the trail of the workflow it was opened from. */
    @Test
    void shouldPreserveTheWorkflowContextForSharedDestinations() {
        assertEquals("Reservations / Reservation R1", text(crumbs(null, ALL).reservationDetail("R1")));
        assertEquals("Front Desk / Arrivals / Reservation R1", text(crumbs("arrivals", ALL).reservationDetail("R1")));
        assertEquals("Front Desk / In-house / Reservation R1", text(crumbs("in-house", ALL).reservationDetail("R1")));
        assertEquals("Front Desk / Check-in / Find Reservation / Reservation R1",
                text(crumbs("find", ALL).reservationDetail("R1")));
        assertEquals("Front Desk / Arrivals / Review", text(crumbs("arrivals", ALL).checkInReview()));
        assertEquals("Front Desk / Check-in / Find Reservation / Review", text(crumbs("find", ALL).checkInReview()));
        assertEquals("Front Desk / Departures / Review", text(crumbs("departures", ALL).checkOutReview()));

        assertEquals("Reservations / Reservation R1 / Folio", text(crumbs(null, ALL).folio(ID, "R1")));
        assertEquals("Front Desk / In-house / Reservation R1 / Folio", text(crumbs("in-house", ALL).folio(ID, "R1")));
        assertEquals(List.of("/front-desk", "/front-desk?view=in-house", "/reservations/" + ID + "?from=in-house", "-"),
                links(crumbs("in-house", ALL).folio(ID, "R1")));
        assertEquals(List.of("/reservations", "/reservations/" + ID, "-"), links(crumbs(null, ALL).folio(ID, "R1")));

        assertEquals("Reservations / Reservation R1 / Change Room", text(crumbs(null, ALL).roomChange(ID, "R1")));
        assertEquals(List.of("/reservations", "/reservations/" + ID, "-"), links(crumbs(null, ALL).roomChange(ID, "R1")));
        assertEquals("Reservations / Change Room", text(crumbs(null, ALL).roomChange(ID, null)));

        assertEquals("Reservations / Reservation R1 / Extend Stay", text(crumbs(null, ALL).stayExtension(ID, "R1")));
        assertEquals(List.of("/reservations", "/reservations/" + ID, "-"), links(crumbs(null, ALL).stayExtension(ID, "R1")));
        assertEquals("Reservations / Extend Stay", text(crumbs(null, ALL).stayExtension(ID, null)));

        assertEquals("Reservations / Reservation R1 / Change Stay Dates", text(crumbs(null, ALL).changeDates(ID, "R1")));
        assertEquals(List.of("/reservations", "/reservations/" + ID, "-"), links(crumbs(null, ALL).changeDates(ID, "R1")));
    }

    /** Confirms the context is carried to the next page by keep(), and an unknown value is ignored. */
    @Test
    void shouldKeepOnlyAKnownContext() {
        assertEquals("/reservations/1/folio?from=departures", crumbs("departures", ALL).keep("/reservations/1/folio"));
        assertEquals("/reservations/1/folio?tab=charges&from=arrivals",
                crumbs("arrivals", ALL).keep("/reservations/1/folio?tab=charges"));
        assertEquals("/reservations/1/folio", crumbs(null, ALL).keep("/reservations/1/folio"));
        Breadcrumbs forged = crumbs("https://evil.example/", ALL);
        assertEquals("/reservations/1/folio", forged.keep("/reservations/1/folio"));
        assertEquals("Reservations / Reservation R1", text(forged.reservationDetail("R1")));
    }

    /** Confirms every trail is at most four levels deep and its last entry is the current page with no link. */
    @Test
    void shouldKeepTrailsConciseWithAnUnlinkedCurrentPage() {
        for (String from : new String[] {null, "arrivals", "in-house", "departures", "find"}) {
            Breadcrumbs c = crumbs(from, ALL);
            List<Function<Breadcrumbs, List<BreadcrumbItem>>> trails = List.of(
                    b -> b.frontDeskView("arrivals"), Breadcrumbs::checkInLanding, Breadcrumbs::checkInFind,
                    Breadcrumbs::checkInReview, b -> b.checkInReassign(ID), Breadcrumbs::checkInWalkIn,
                    Breadcrumbs::checkInWalkInSummary, Breadcrumbs::checkInOta, Breadcrumbs::checkInOtaSummary,
                    Breadcrumbs::checkOutSearch, Breadcrumbs::checkOutReview, Breadcrumbs::checkOutComplete,
                    Breadcrumbs::reservationCreate, Breadcrumbs::reservationEdit,
                    b -> b.reservationDetail("R1"), b -> b.folio(ID, "R1"), b -> b.roomChange(ID, "R1"), b -> b.stayExtension(ID, "R1"), b -> b.changeDates(ID, "R1"),
                    b -> b.guestDetail("G1"));
            for (var trail : trails) {
                List<BreadcrumbItem> items = trail.apply(c);
                assertTrue(items.size() >= 2 && items.size() <= 4, from + ": " + text(items));
                assertNull(items.get(items.size() - 1).href(), "the current page is not a link: " + text(items));
            }
        }
    }

    /** Confirms an entry the user cannot open is plain text, never a dead link. */
    @Test
    void shouldNotLinkADestinationTheUserCannotOpen() {
        assertEquals(List.of("-", "-"), links(crumbs(null, "PERM_MANAGE_GUEST").frontDeskView("arrivals")));
        assertEquals(List.of("/front-desk", "-"), links(crumbs(null, "PERM_CHECK_OUT").frontDeskView("departures")));
        assertEquals(List.of("-", "-"), links(crumbs(null).reservationDetail("R1")));
        assertEquals(List.of("/reservations", "-"), links(crumbs(null, "PERM_VIEW_BOOKING").reservationDetail("R1")));
    }

    /** Confirms the trails are translated. */
    @Test
    void shouldTranslateTheTrails() {
        LocaleContextHolder.setLocale(Locale.forLanguageTag("vi"));
        assertEquals("Lễ tân / Nhận phòng / Tìm đặt phòng", text(crumbs(null, ALL).checkInFind()));
        assertEquals("Đặt phòng / Đặt phòng R1 / Đổi phòng", text(crumbs(null, ALL).roomChange(ID, "R1")));
        assertEquals("Đặt phòng / Đặt phòng R1 / Gia hạn lưu trú", text(crumbs(null, ALL).stayExtension(ID, "R1")));
        assertEquals("Đặt phòng / Đặt phòng R1 / Đổi ngày đặt phòng", text(crumbs(null, ALL).changeDates(ID, "R1")));
    }
}
