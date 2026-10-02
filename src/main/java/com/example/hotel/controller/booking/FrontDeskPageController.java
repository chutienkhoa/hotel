package com.example.hotel.controller.booking;

import com.example.hotel.common.PaginationSupport;
import com.example.hotel.common.TableSorts;
import com.example.hotel.dto.booking.request.FrontDeskSearchCriteria;
import com.example.hotel.dto.booking.response.FrontDeskArrivalRow;
import com.example.hotel.dto.booking.response.FrontDeskStayRow;
import com.example.hotel.service.booking.FrontDeskQueryService;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.data.domain.Page;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
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
     * absent or unknown view falls back to the first authorized one (Arrivals, otherwise Departures). The
     * selected view's worklist is paginated, optionally searched and optionally sorted (Batch 3A): this narrows
     * and re-orders the view's already business-scoped result set only, and never changes which
     * Reservations/Stays qualify (§9.2.3).
     *
     * @param view optional view: {@code arrivals}, {@code departures} or {@code in-house}
     * @param searchCriteria normalized optional search/sort state for the selected view
     * @param page optional requested zero-based page number
     * @param model model used to render the page
     * @param authentication current user authentication
     * @return the Front Desk template, or a redirect to the last valid page when the requested page is out of range
     */
    @GetMapping("/front-desk")
    @PreAuthorize("hasAnyAuthority('PERM_CHECK_IN', 'PERM_CHECK_OUT')")
    public String show(
            @RequestParam(required = false) String view,
            @ModelAttribute("searchCriteria") FrontDeskSearchCriteria searchCriteria,
            @RequestParam(required = false) String page,
            Model model,
            Authentication authentication) {
        boolean canArrivals = has(authentication, "PERM_CHECK_IN");
        boolean canStays = has(authentication, "PERM_CHECK_OUT");
        String selected = resolve(view, canArrivals, canStays);
        searchCriteria.normalize();
        int requestedPage = PaginationSupport.parsePage(page);
        model.addAttribute("view", selected);
        model.addAttribute("canArrivals", canArrivals);
        model.addAttribute("canStays", canStays);
        model.addAttribute("canChangeRoom", has(authentication, "PERM_CHANGE_ROOM"));
        model.addAttribute("canExtendStay", has(authentication, "PERM_EXTEND_STAY"));
        model.addAttribute("hotelToday", queryService.hotelToday());
        return switch (selected) {
            case ARRIVALS -> showArrivals(searchCriteria, requestedPage, model);
            case DEPARTURES -> showDepartures(searchCriteria, requestedPage, model, authentication);
            default -> showInHouse(searchCriteria, requestedPage, model);
        };
    }

    /**
     * Loads one page of Arrivals and adds the Needs-Attention/Ready split and pagination attributes.
     *
     * @param searchCriteria normalized optional search/sort state
     * @param requestedPage requested zero-based page number
     * @param model model used to render the page
     * @return the Front Desk template, or an out-of-range redirect
     */
    private String showArrivals(FrontDeskSearchCriteria searchCriteria, int requestedPage, Model model) {
        Page<FrontDeskArrivalRow> arrivalsPage = queryService.arrivals(searchCriteria, requestedPage);
        String sortKey = TableSorts.FRONT_DESK_ARRIVALS.key(searchCriteria.getSort(), searchCriteria.getDir());
        String sortDir = TableSorts.FRONT_DESK_ARRIVALS.activeDirection(searchCriteria.getSort(), searchCriteria.getDir());
        Map<String, String> filters = filters(ARRIVALS, searchCriteria);
        String redirect = PaginationSupport.redirectWhenOutOfRange(
                arrivalsPage, requestedPage, "/front-desk", filters, sortKey, sortDir);
        if (redirect != null) {
            return redirect;
        }
        model.addAttribute("arrivalsPage", arrivalsPage);
        model.addAttribute("attentionArrivals", arrivalsPage.getContent().stream().filter(FrontDeskArrivalRow::needsAttention).toList());
        model.addAttribute("readyArrivals", arrivalsPage.getContent().stream().filter(row -> !row.needsAttention()).toList());
        PaginationSupport.populate(model, arrivalsPage, "/front-desk", filters, sortKey, sortDir);
        return "front-desk/index";
    }

    /**
     * Loads one page of Departures and adds the Needs-Attention/Ready split and pagination attributes. Outstanding
     * amount visibility still follows {@code PERM_MANAGE_PAYMENT} exactly as before (§9.2.4); this method only adds
     * search/sort/pagination on top.
     *
     * @param searchCriteria normalized optional search/sort state
     * @param requestedPage requested zero-based page number
     * @param model model used to render the page
     * @param authentication current user authentication
     * @return the Front Desk template, or an out-of-range redirect
     */
    private String showDepartures(
            FrontDeskSearchCriteria searchCriteria, int requestedPage, Model model, Authentication authentication) {
        Page<FrontDeskStayRow> departuresPage =
                queryService.departures(has(authentication, "PERM_MANAGE_PAYMENT"), searchCriteria, requestedPage);
        String sortKey = TableSorts.FRONT_DESK_DEPARTURES.key(searchCriteria.getSort(), searchCriteria.getDir());
        String sortDir = TableSorts.FRONT_DESK_DEPARTURES.activeDirection(searchCriteria.getSort(), searchCriteria.getDir());
        Map<String, String> filters = filters(DEPARTURES, searchCriteria);
        String redirect = PaginationSupport.redirectWhenOutOfRange(
                departuresPage, requestedPage, "/front-desk", filters, sortKey, sortDir);
        if (redirect != null) {
            return redirect;
        }
        model.addAttribute("departuresPage", departuresPage);
        model.addAttribute("attentionDepartures", departuresPage.getContent().stream().filter(FrontDeskStayRow::needsAttention).toList());
        model.addAttribute("readyDepartures", departuresPage.getContent().stream().filter(row -> !row.needsAttention()).toList());
        PaginationSupport.populate(model, departuresPage, "/front-desk", filters, sortKey, sortDir);
        return "front-desk/index";
    }

    /**
     * Loads one page of In-house and adds the pagination attributes.
     *
     * @param searchCriteria normalized optional search/sort state
     * @param requestedPage requested zero-based page number
     * @param model model used to render the page
     * @return the Front Desk template, or an out-of-range redirect
     */
    private String showInHouse(FrontDeskSearchCriteria searchCriteria, int requestedPage, Model model) {
        Page<FrontDeskStayRow> inHousePage = queryService.inHouse(searchCriteria, requestedPage);
        String sortKey = TableSorts.FRONT_DESK_IN_HOUSE.key(searchCriteria.getSort(), searchCriteria.getDir());
        String sortDir = TableSorts.FRONT_DESK_IN_HOUSE.activeDirection(searchCriteria.getSort(), searchCriteria.getDir());
        Map<String, String> filters = filters(IN_HOUSE, searchCriteria);
        String redirect = PaginationSupport.redirectWhenOutOfRange(
                inHousePage, requestedPage, "/front-desk", filters, sortKey, sortDir);
        if (redirect != null) {
            return redirect;
        }
        model.addAttribute("inHousePage", inHousePage);
        PaginationSupport.populate(model, inHousePage, "/front-desk", filters, sortKey, sortDir);
        return "front-desk/index";
    }

    /**
     * Builds the pagination/sort link filter map for one view, preserving the active view and search fragment.
     *
     * @param view the active view
     * @param searchCriteria normalized optional search/sort state
     * @return the filters to echo on every pagination/sort link for this view
     */
    private Map<String, String> filters(String view, FrontDeskSearchCriteria searchCriteria) {
        Map<String, String> filters = new LinkedHashMap<>();
        filters.put("view", view);
        if (searchCriteria.getSearch() != null) {
            filters.put("search", searchCriteria.getSearch());
        }
        return filters;
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
