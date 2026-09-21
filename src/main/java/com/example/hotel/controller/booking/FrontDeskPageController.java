package com.example.hotel.controller.booking;

import com.example.hotel.service.booking.FrontDeskQueryService;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;

/**
 * Serves the Front Desk workspace: a read-only operational worklist (Arrivals, Departures, In-house) that composes
 * existing domain information and links to existing operations. It performs no state change and grants no
 * capability: Arrivals needs CHECK_IN, Departures and In-house need CHECK_OUT, and every linked operation keeps its
 * own permission. Only the requested, authorized view is loaded.
 */
@Controller
public class FrontDeskPageController {

    private static final String ARRIVALS = "arrivals";
    private static final String DEPARTURES = "departures";
    private static final String IN_HOUSE = "in-house";

    private final FrontDeskQueryService queryService;

    /**
     * Creates the controller.
     *
     * @param queryService read model for the workspace
     */
    public FrontDeskPageController(FrontDeskQueryService queryService) {
        this.queryService = queryService;
    }

    /**
     * Displays one Front Desk view. An explicitly requested view the user is not authorized for is refused; an
     * absent or unknown view falls back to the first authorized one (Arrivals, otherwise Departures).
     *
     * @param view optional view: {@code arrivals}, {@code departures} or {@code in-house}
     * @param model model used to render the page
     * @param authentication current user authentication
     * @return the Front Desk template
     */
    @GetMapping("/front-desk")
    @PreAuthorize("hasAnyAuthority('PERM_CHECK_IN', 'PERM_CHECK_OUT')")
    public String show(@RequestParam(required = false) String view, Model model, Authentication authentication) {
        boolean canArrivals = has(authentication, "PERM_CHECK_IN");
        boolean canStays = has(authentication, "PERM_CHECK_OUT");
        String selected = resolve(view, canArrivals, canStays);
        model.addAttribute("view", selected);
        model.addAttribute("canArrivals", canArrivals);
        model.addAttribute("canStays", canStays);
        model.addAttribute("canChangeRoom", has(authentication, "PERM_CHANGE_ROOM"));
        model.addAttribute("canExtendStay", has(authentication, "PERM_MANAGE_BOOKING"));
        model.addAttribute("hotelToday", queryService.hotelToday());
        switch (selected) {
            case ARRIVALS -> {
                var rows = queryService.arrivals();
                model.addAttribute("attentionArrivals", rows.stream().filter(row -> row.needsAttention()).toList());
                model.addAttribute("readyArrivals", rows.stream().filter(row -> !row.needsAttention()).toList());
            }
            case DEPARTURES -> {
                var rows = queryService.departures(has(authentication, "PERM_MANAGE_PAYMENT"));
                model.addAttribute("attentionDepartures", rows.stream().filter(row -> row.needsAttention()).toList());
                model.addAttribute("readyDepartures", rows.stream().filter(row -> !row.needsAttention()).toList());
            }
            default -> model.addAttribute("inHouseRows", queryService.inHouse());
        }
        return "front-desk/index";
    }

    private String resolve(String requested, boolean canArrivals, boolean canStays) {
        boolean known = ARRIVALS.equals(requested) || DEPARTURES.equals(requested) || IN_HOUSE.equals(requested);
        if (known) {
            boolean allowed = ARRIVALS.equals(requested) ? canArrivals : canStays;
            if (!allowed) {
                throw new AccessDeniedException("Front Desk view not permitted");
            }
            return requested;
        }
        return canArrivals ? ARRIVALS : DEPARTURES;
    }

    private boolean has(Authentication authentication, String authority) {
        return authentication.getAuthorities().stream().anyMatch(granted -> authority.equals(granted.getAuthority()));
    }
}
