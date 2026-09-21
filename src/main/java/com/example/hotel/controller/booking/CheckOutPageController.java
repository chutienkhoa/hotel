package com.example.hotel.controller.booking;

import com.example.hotel.common.TableSorts;
import com.example.hotel.common.PaginationSupport;
import com.example.hotel.dto.booking.request.ReservationSearchCriteria;
import com.example.hotel.dto.booking.response.CheckOutListItemResponse;
import com.example.hotel.dto.booking.response.CheckOutReviewResponse;
import com.example.hotel.service.booking.CheckOutQueryService;
import com.example.hotel.service.booking.ReservationService;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

/**
 * Serves the dedicated Check-out operational screens (search/queue and Review) and delegates
 * every read to {@link CheckOutQueryService} and the final state-changing action to the existing
 * authoritative {@link ReservationService#checkOut}. This controller introduces no second
 * Check-out business flow: the existing Reservation Detail and Folio Check-out actions remain
 * fully functional and converge on the same service call.
 */
@Controller
@RequestMapping("/check-out")
public class CheckOutPageController {

    private final com.example.hotel.common.i18n.UiMessages messages;
    private final CheckOutQueryService checkOutQueryService;
    private final ReservationService reservationService;

    /**
     * Creates the Check-out MVC controller with its collaborators.
     *
     * @param checkOutQueryService service implementing the Check-out operational search/Review reads
     * @param reservationService service holding the authoritative checkOut lifecycle operation
     * @param messageSource message source for localized rejections
     */
    public CheckOutPageController(
            CheckOutQueryService checkOutQueryService,
            ReservationService reservationService,
            org.springframework.context.MessageSource messageSource) {
        this.messages = new com.example.hotel.common.i18n.UiMessages(messageSource);
        this.checkOutQueryService = checkOutQueryService;
        this.reservationService = reservationService;
    }

    /**
     * Displays a local-database-only search for CHECKED_IN Reservations to check out.
     *
     * @param searchCriteria submitted Reservation Number / Guest / Room filters
     * @param page zero-based requested page number
     * @param model model used to render the search page
     * @return the Check-out search template name
     */
    @GetMapping
    @PreAuthorize("hasAuthority('PERM_CHECK_OUT')")
    public String search(
            @ModelAttribute("searchCriteria") ReservationSearchCriteria searchCriteria,
            @RequestParam(required = false) String page,
            Model model) {
        searchCriteria.normalizeReservationNumber();
        searchCriteria.normalizeGuest();
        searchCriteria.normalizeRoom();
        int requestedPage = PaginationSupport.parsePage(page);
        Page<CheckOutListItemResponse> reservationPage = checkOutQueryService.search(searchCriteria, requestedPage);
        String sortKey = TableSorts.CHECK_OUT.key(searchCriteria.getSort(), searchCriteria.getDir());
        String sortDir = TableSorts.CHECK_OUT.activeDirection(searchCriteria.getSort(), searchCriteria.getDir());
        Map<String, String> filters = new LinkedHashMap<>();
        if (searchCriteria.getReservationNumber() != null) {
            filters.put("reservationNumber", searchCriteria.getReservationNumber());
        }
        if (searchCriteria.getGuest() != null) {
            filters.put("guest", searchCriteria.getGuest());
        }
        if (searchCriteria.getRoom() != null) {
            filters.put("room", searchCriteria.getRoom());
        }
        String redirect =
                PaginationSupport.redirectWhenOutOfRange(reservationPage, requestedPage, "/check-out", filters, sortKey, sortDir);
        if (redirect != null) {
            return redirect;
        }
        model.addAttribute("reservationPage", reservationPage);
        PaginationSupport.populate(model, reservationPage, "/check-out", filters, sortKey, sortDir);
        return "check-out/search";
    }

    /**
     * Displays the read-only Check-out Review for one Reservation.
     *
     * @param id Reservation identifier
     * @param model model used to render the Review page
     * @return the Check-out Review template name
     */
    @GetMapping("/{id}")
    @PreAuthorize("hasAuthority('PERM_CHECK_OUT')")
    public String review(@PathVariable UUID id, Model model, org.springframework.security.core.Authentication authentication) {
        CheckOutReviewResponse review = checkOutQueryService.review(id);
        model.addAttribute("review", review);
        // The existing Stay Extension permission; never granted implicitly by the template.
        model.addAttribute("canExtendStay", authentication.getAuthorities().stream()
                .anyMatch(authority -> "PERM_MANAGE_BOOKING".equals(authority.getAuthority())));
        return "check-out/review";
    }

    /**
     * Submits the explicit human confirmation for a Reservation's Check-out, delegating entirely
     * to the existing authoritative {@link ReservationService#checkOut}. Every precondition
     * (Reservation/Stay status, Outstanding, current Room state) is re-validated independently by
     * that service regardless of what the Review page displayed, so stale Review data can never
     * bypass it.
     *
     * @param id Reservation identifier
     * @param redirectAttributes attributes used to show post-redirect feedback
     * @return a redirect to the Check-out queue on success, so Staff can process the next
     *     departing guest, or back to Review on failure
     */
    @PostMapping("/{id}/confirm")
    @PreAuthorize("hasAuthority('PERM_CHECK_OUT')")
    public String confirm(@PathVariable UUID id, RedirectAttributes redirectAttributes) {
        try {
            reservationService.checkOut(id);
            redirectAttributes.addFlashAttribute("successMessage", "Check-out completed successfully.");
            return "redirect:/check-out";
        } catch (ResponseStatusException exception) {
            redirectAttributes.addFlashAttribute("errorMessage", safeMessage(exception));
            return "redirect:/check-out/" + id;
        }
    }

    /**
     * Selects a user-safe message from a known service exception.
     *
     * @param exception exception raised by a Check-out operation
     * @return a safe message for the browser
     */
    private String safeMessage(ResponseStatusException exception) {
        return messages.error(exception);
    }
}
