package com.example.hotel.controller.common;

import com.example.hotel.common.i18n.UiMessages;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;
import org.springframework.security.core.Authentication;

/**
 * Builds the breadcrumb trail of a page for the current request. A trail shows the workflow the user is in, not the URL
 * hierarchy, so the same destination (Reservation Detail, Folio, Check-in Review) gets a different trail depending on the
 * context it was opened from. That context travels in the {@code from} query parameter of the link that opened the page
 * and is limited to the known values below; anything else is ignored, so a trail can never be forged into pointing at
 * an arbitrary place. A trail is navigation only: it is not browser history and grants no access, and an entry whose
 * destination the user may not open is shown as plain text.
 */
public final class Breadcrumbs {

    /** Query parameter that carries the context a page was opened from. */
    public static final String CONTEXT_PARAMETER = "from";

    private static final String ARRIVALS = "arrivals";
    private static final String IN_HOUSE = "in-house";
    private static final String DEPARTURES = "departures";
    private static final String FIND = "find";
    private static final Set<String> CONTEXTS = Set.of(ARRIVALS, IN_HOUSE, DEPARTURES, FIND);

    private static final String FRONT_DESK = "/front-desk";
    private static final String CHECK_IN = "/check-in";
    private static final String CHECK_OUT = "/check-out";
    private static final String RESERVATIONS = "/reservations";

    private final UiMessages messages;
    private final Locale locale;
    private final Set<String> authorities;
    private final String context;

    /**
     * Creates the builder for one request.
     *
     * @param messages localized text source
     * @param locale the locale of this request (the one the page itself is rendered in, which on a language switch is
     *     already the newly chosen one)
     * @param authentication the current user, used to leave out links the user cannot open
     * @param requestedContext the raw {@code from} request parameter, possibly {@code null} or unknown
     */
    public Breadcrumbs(UiMessages messages, Locale locale, Authentication authentication, String requestedContext) {
        this.messages = messages;
        this.locale = locale;
        this.authorities = authentication == null
                ? Set.of()
                : authentication.getAuthorities().stream()
                        .map(granted -> granted.getAuthority())
                        .collect(java.util.stream.Collectors.toSet());
        this.context = requestedContext != null && CONTEXTS.contains(requestedContext) ? requestedContext : null;
    }

    /**
     * Appends the current context to a path, so a page opened from a workflow keeps that workflow's trail on the next
     * page. Returns the path unchanged when there is no context.
     *
     * @param path application-relative path, with or without a query string
     * @return the path carrying the context
     */
    public String keep(String path) {
        if (context == null) {
            return path;
        }
        return path + (path.contains("?") ? "&" : "?") + CONTEXT_PARAMETER + "=" + context;
    }

    // ---------------------------------------------------------------------------------------------- Front Desk

    /**
     * Front Desk / Arrivals, In-house or Departures.
     *
     * @param view the Front Desk view: arrivals, in-house or departures
     * @return the trail
     */
    public List<BreadcrumbItem> frontDeskView(String view) {
        return trail(frontDesk(), currentText(viewLabel(view)));
    }

    /** @return Front Desk / Check-in */
    public List<BreadcrumbItem> checkInLanding() {
        return trail(frontDesk(), current("navigation.checkIn"));
    }

    /** @return Front Desk / Check-in / Find Reservation */
    public List<BreadcrumbItem> checkInFind() {
        return trail(frontDesk(), checkIn(), current("navigation.crumb.findReservation"));
    }

    /**
     * Check-in Review: from Arrivals it is Front Desk / Arrivals / Review, from Find Reservation it is Front Desk /
     * Check-in / Find Reservation / Review, otherwise Front Desk / Check-in / Review.
     *
     * @return the trail
     */
    public List<BreadcrumbItem> checkInReview() {
        if (ARRIVALS.equals(context)) {
            return trail(frontDesk(), viewLink(ARRIVALS), current("navigation.crumb.review"));
        }
        if (FIND.equals(context)) {
            return trail(frontDesk(), checkIn(), link("navigation.crumb.findReservation", CHECK_IN + "/existing", "PERM_CHECK_IN"),
                    current("navigation.crumb.review"));
        }
        return trail(frontDesk(), checkIn(), current("navigation.crumb.review"));
    }

    /**
     * Reassign Room, reached from Check-in Review.
     *
     * @param reservationId the Reservation whose Check-in Review is the parent
     * @return Front Desk / Check-in / Review / Reassign Room
     */
    public List<BreadcrumbItem> checkInReassign(UUID reservationId) {
        return trail(frontDesk(), checkIn(),
                link("navigation.crumb.review", CHECK_IN + "/reservations/" + reservationId, "PERM_CHECK_IN"),
                current("navigation.crumb.reassignRoom"));
    }

    /** @return Front Desk / Check-in / Walk-in */
    public List<BreadcrumbItem> checkInWalkIn() {
        return trail(frontDesk(), checkIn(), current("navigation.crumb.walkIn"));
    }

    /** @return Front Desk / Check-in / Walk-in / Summary */
    public List<BreadcrumbItem> checkInWalkInSummary() {
        return trail(frontDesk(), checkIn(), link("navigation.crumb.walkIn", CHECK_IN + "/walk-in", "PERM_CHECK_IN"),
                current("navigation.crumb.summary"));
    }

    /** @return Front Desk / Check-in / OTA Reservation */
    public List<BreadcrumbItem> checkInOta() {
        return trail(frontDesk(), checkIn(), current("navigation.crumb.otaReservation"));
    }

    /** @return Front Desk / Check-in / OTA Reservation / Summary */
    public List<BreadcrumbItem> checkInOtaSummary() {
        return trail(frontDesk(), checkIn(),
                link("navigation.crumb.otaReservation", CHECK_IN + "/ota-entry", "PERM_CHECK_IN"),
                current("navigation.crumb.summary"));
    }

    /** @return Front Desk / Check-out */
    public List<BreadcrumbItem> checkOutSearch() {
        return trail(frontDesk(), current("navigation.checkOut"));
    }

    /** @return Front Desk / Departures / Review when opened from Departures, otherwise Front Desk / Check-out / Review */
    public List<BreadcrumbItem> checkOutReview() {
        if (DEPARTURES.equals(context)) {
            return trail(frontDesk(), viewLink(DEPARTURES), current("navigation.crumb.review"));
        }
        return trail(frontDesk(), checkOut(), current("navigation.crumb.review"));
    }

    /** @return Front Desk / Check-out / Complete */
    public List<BreadcrumbItem> checkOutComplete() {
        return trail(frontDesk(), checkOut(), current("navigation.crumb.complete"));
    }

    // ---------------------------------------------------------------------------------------------- Reservations

    /** @return Reservations / Create Reservation */
    public List<BreadcrumbItem> reservationCreate() {
        return trail(reservations(), current("reservation.create.title"));
    }

    /** @return Reservations / Edit Reservation */
    public List<BreadcrumbItem> reservationEdit() {
        return trail(reservations(), current("navigation.crumb.editReservation"));
    }

    /**
     * Reservation Detail. Opened from the list it is Reservations / Reservation R...; opened from a Front Desk view or
     * Find Reservation it keeps that workflow: Front Desk / Arrivals / Reservation R...
     *
     * @param reservationNumber the Reservation number shown in the current entry
     * @return the trail
     */
    public List<BreadcrumbItem> reservationDetail(String reservationNumber) {
        return trail(reservationParents(), currentText(reservationLabel(reservationNumber)));
    }

    /**
     * Folio of a Reservation, keeping the workflow the Reservation was opened from where the depth allows.
     *
     * @param reservationId the Reservation identifier
     * @param reservationNumber the Reservation number shown in the parent entry
     * @return the trail
     */
    public List<BreadcrumbItem> folio(UUID reservationId, String reservationNumber) {
        List<BreadcrumbItem> parents = FIND.equals(context) ? trail(reservations()) : reservationParents();
        String detail = RESERVATIONS + "/" + reservationId;
        return trail(parents,
                new BreadcrumbItem(reservationLabel(reservationNumber),
                        allowed("PERM_VIEW_BOOKING") ? (FIND.equals(context) ? detail : keep(detail)) : null),
                current("navigation.crumb.folio"));
    }

    /**
     * Change Room (select the new room), a step of the Reservation workflow. Without a known Reservation number the
     * Reservation entry is left out, since it could not be named.
     *
     * @param reservationId the Reservation identifier
     * @param reservationNumber the Reservation number shown in the parent entry, or {@code null} when unknown
     * @return Reservations / Reservation R... / Change Room
     */
    public List<BreadcrumbItem> roomChange(UUID reservationId, String reservationNumber) {
        if (reservationNumber == null) {
            return trail(reservations(), current("reservation.roomChange.title"));
        }
        List<BreadcrumbItem> parents = FIND.equals(context) ? trail(reservations()) : reservationParents();
        String detail = RESERVATIONS + "/" + reservationId;
        return trail(parents,
                new BreadcrumbItem(reservationLabel(reservationNumber),
                        allowed("PERM_VIEW_BOOKING") ? (FIND.equals(context) ? detail : keep(detail)) : null),
                current("reservation.roomChange.title"));
    }

    // ---------------------------------------------------------------------------------------------- Guests

    /**
     * Guest Detail.
     *
     * @param guestCode the Guest code shown in the current entry
     * @return Guests / G000001
     */
    public List<BreadcrumbItem> guestDetail(String guestCode) {
        return trail(link("navigation.guests", "/guests", "PERM_MANAGE_GUEST"), currentText(guestCode));
    }

    // ---------------------------------------------------------------------------------------------- helpers

    private List<BreadcrumbItem> reservationParents() {
        if (ARRIVALS.equals(context) || IN_HOUSE.equals(context) || DEPARTURES.equals(context)) {
            return trail(frontDesk(), viewLink(context));
        }
        if (FIND.equals(context)) {
            return trail(frontDesk(), checkIn(), link("navigation.crumb.findReservation", CHECK_IN + "/existing", "PERM_CHECK_IN"));
        }
        return trail(reservations());
    }

    private String reservationLabel(String reservationNumber) {
        return messages.get(locale, "navigation.crumb.reservation", reservationNumber);
    }

    private BreadcrumbItem frontDesk() {
        return link("navigation.frontDesk", FRONT_DESK, "PERM_CHECK_IN", "PERM_CHECK_OUT");
    }

    private BreadcrumbItem checkIn() {
        return link("navigation.checkIn", CHECK_IN, "PERM_CHECK_IN");
    }

    private BreadcrumbItem checkOut() {
        return link("navigation.checkOut", CHECK_OUT, "PERM_CHECK_OUT");
    }

    private BreadcrumbItem reservations() {
        return link("navigation.reservations", RESERVATIONS, "PERM_VIEW_BOOKING");
    }

    private BreadcrumbItem viewLink(String view) {
        return link(viewKey(view), FRONT_DESK + "?view=" + view, "PERM_CHECK_IN", "PERM_CHECK_OUT");
    }

    private String viewLabel(String view) {
        return messages.get(locale, viewKey(view));
    }

    private static String viewKey(String view) {
        return switch (view) {
            case IN_HOUSE -> "reservation.frontDesk.tab.inHouse";
            case DEPARTURES -> "reservation.frontDesk.tab.departures";
            default -> "reservation.frontDesk.tab.arrivals";
        };
    }

    /** A link entry, or plain text when the user holds none of the authorities that open its destination. */
    private BreadcrumbItem link(String key, String href, String... anyOfAuthorities) {
        return new BreadcrumbItem(messages.get(locale, key), allowed(anyOfAuthorities) ? href : null);
    }

    /** The current page, named by a message key. */
    private BreadcrumbItem current(String key) {
        return new BreadcrumbItem(messages.get(locale, key), null);
    }

    /** The current page, named by already-resolved text (for example a Reservation number). */
    private BreadcrumbItem currentText(String label) {
        return new BreadcrumbItem(label, null);
    }

    private boolean allowed(String... anyOfAuthorities) {
        for (String authority : anyOfAuthorities) {
            if (authorities.contains(authority)) {
                return true;
            }
        }
        return false;
    }

    private static List<BreadcrumbItem> trail(BreadcrumbItem... items) {
        return List.of(items);
    }

    private static List<BreadcrumbItem> trail(List<BreadcrumbItem> parents, BreadcrumbItem... items) {
        List<BreadcrumbItem> all = new ArrayList<>(parents);
        all.addAll(List.of(items));
        return List.copyOf(all);
    }
}
