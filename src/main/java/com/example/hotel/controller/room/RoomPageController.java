package com.example.hotel.controller.room;

import com.example.hotel.dto.room.request.RoomCreateRequest;
import com.example.hotel.dto.room.request.RoomSearchCriteria;
import com.example.hotel.dto.room.request.RoomUpdateRequest;
import com.example.hotel.dto.room.response.RoomResponse;
import com.example.hotel.entity.room.RoomStatus;
import com.example.hotel.service.room.RoomQueryService;
import com.example.hotel.service.room.RoomService;
import com.example.hotel.service.room.RoomTypeQueryService;
import jakarta.validation.Valid;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;
import org.springframework.data.domain.Page;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.validation.BindingResult;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;
import org.springframework.web.util.UriComponentsBuilder;

/** Serves CSRF-protected Thymeleaf pages for authorized Room Management. */
@Controller
public class RoomPageController {

    private final RoomService roomService;
    private final RoomQueryService roomQueryService;
    private final RoomTypeQueryService roomTypeQueryService;

    /**
     * Creates the page controller with services used to manage rooms and load read-only RoomTypes.
     *
     * @param roomService service used to load and update room profiles
     * @param roomQueryService service used to load paginated, filtered Room list data
     * @param roomTypeQueryService service used to load RoomType selections
     */
    public RoomPageController(
            RoomService roomService, RoomQueryService roomQueryService, RoomTypeQueryService roomTypeQueryService) {
        this.roomService = roomService;
        this.roomQueryService = roomQueryService;
        this.roomTypeQueryService = roomTypeQueryService;
    }

    /**
     * Displays a filterable, paginated Room list for Room Management users.
     *
     * @param searchCriteria optional Room list filters bound from the request
     * @param page zero-based requested page number
     * @param model model used to render the page
     * @param authentication current browser authentication
     * @return the room list template
     */
    @GetMapping("/rooms")
    @PreAuthorize("hasAuthority('PERM_MANAGE_ROOM')")
    public String list(
            @ModelAttribute("searchCriteria") RoomSearchCriteria searchCriteria,
            @RequestParam(required = false) Integer page,
            Model model,
            Authentication authentication) {
        addAuthorizationAttributes(model, authentication);
        searchCriteria.normalize();
        model.addAttribute("roomTypes", roomTypeQueryService.findAll());
        model.addAttribute("roomStatuses", RoomStatus.values());
        Page<RoomResponse> roomPage = roomQueryService.findPage(searchCriteria, page == null ? 0 : page);
        model.addAttribute("roomPage", roomPage);
        model.addAttribute("filterQueryString", filterQueryString(searchCriteria));
        addPaginationAttributes(model, roomPage);
        return "room/list";
    }

    /**
     * Displays one room profile.
     *
     * @param id room identifier
     * @param model model used to render the page
     * @param authentication current browser authentication
     * @return the room detail template
     */
    @GetMapping("/rooms/{id}")
    @PreAuthorize("hasAuthority('PERM_MANAGE_ROOM')")
    public String detail(@PathVariable UUID id, Model model, Authentication authentication) {
        addAuthorizationAttributes(model, authentication);
        model.addAttribute("room", roomService.findById(id));
        return "room/detail";
    }

    /**
     * Displays an empty room creation form.
     *
     * @param model model used to render the page
     * @param authentication current browser authentication
     * @return the room form template
     */
    @GetMapping("/rooms/new")
    @PreAuthorize("hasAuthority('PERM_MANAGE_ROOM')")
    public String createForm(Model model, Authentication authentication) {
        addFormAttributes(model, new RoomCreateRequest(null, null, null), authentication);
        return "room/form";
    }

    /**
     * Creates a room from a browser form and redirects to its detail page after success.
     *
     * @param roomForm validated form data
     * @param bindingResult structural validation result
     * @param model model used to redisplay invalid input
     * @param authentication current browser authentication
     * @param redirectAttributes attributes used to show post-redirect feedback
     * @return a detail redirect or the form template after validation failure
     */
    @PostMapping("/rooms")
    @PreAuthorize("hasAuthority('PERM_MANAGE_ROOM')")
    public String create(
            @Valid @ModelAttribute("roomForm") RoomCreateRequest roomForm,
            BindingResult bindingResult,
            Model model,
            Authentication authentication,
            RedirectAttributes redirectAttributes) {
        if (bindingResult.hasErrors()) {
            addFormAttributes(model, roomForm, authentication);
            return "room/form";
        }
        try {
            RoomResponse room = roomService.create(roomForm);
            redirectAttributes.addFlashAttribute("successMessage", "Room created successfully.");
            return "redirect:/rooms/" + room.id();
        } catch (ResponseStatusException exception) {
            addFormAttributes(model, roomForm, authentication);
            model.addAttribute("errorMessage", safeMessage(exception));
            return "room/form";
        }
    }

    /**
     * Displays a pre-populated room update form.
     *
     * @param id room identifier
     * @param model model used to render the page
     * @param authentication current browser authentication
     * @return the room form template
     */
    @GetMapping("/rooms/{id}/edit")
    @PreAuthorize("hasAuthority('PERM_MANAGE_ROOM')")
    public String updateForm(@PathVariable UUID id, Model model, Authentication authentication) {
        RoomResponse room = roomService.findById(id);
        addFormAttributes(model, toUpdateRequest(room), authentication);
        model.addAttribute("room", room);
        return "room/form";
    }

    /**
     * Updates a room from a browser form and redirects to its detail page after success.
     *
     * @param id room identifier
     * @param roomForm validated form data
     * @param bindingResult structural validation result
     * @param model model used to redisplay invalid input
     * @param authentication current browser authentication
     * @param redirectAttributes attributes used to show post-redirect feedback
     * @return a detail redirect or the form template after validation failure
     */
    @PostMapping("/rooms/{id}")
    @PreAuthorize("hasAuthority('PERM_MANAGE_ROOM')")
    public String update(
            @PathVariable UUID id,
            @Valid @ModelAttribute("roomForm") RoomUpdateRequest roomForm,
            BindingResult bindingResult,
            Model model,
            Authentication authentication,
            RedirectAttributes redirectAttributes) {
        if (bindingResult.hasErrors()) {
            addFormAttributes(model, roomForm, authentication);
            model.addAttribute("room", roomService.findById(id));
            return "room/form";
        }
        try {
            roomService.update(id, roomForm);
            redirectAttributes.addFlashAttribute("successMessage", "Room updated successfully.");
            return "redirect:/rooms/" + id;
        } catch (ResponseStatusException exception) {
            addFormAttributes(model, roomForm, authentication);
            model.addAttribute("room", roomService.findById(id));
            model.addAttribute("errorMessage", safeMessage(exception));
            return "room/form";
        }
    }

    /**
     * Starts cleaning a dirty room from a CSRF-protected browser form.
     *
     * @param id room identifier
     * @param redirectAttributes attributes used to show post-redirect feedback
     * @return a detail redirect after the operation result is recorded
     */
    @PostMapping("/rooms/{id}/start-cleaning")
    @PreAuthorize("hasAuthority('PERM_MANAGE_ROOM')")
    public String startCleaning(@PathVariable UUID id, RedirectAttributes redirectAttributes) {
        return executeOperation(id, roomService::startCleaning, "Cleaning started successfully.", redirectAttributes);
    }

    /**
     * Finishes cleaning a room from a CSRF-protected browser form.
     *
     * @param id room identifier
     * @param redirectAttributes attributes used to show post-redirect feedback
     * @return a detail redirect after the operation result is recorded
     */
    @PostMapping("/rooms/{id}/finish-cleaning")
    @PreAuthorize("hasAuthority('PERM_MANAGE_ROOM')")
    public String finishCleaning(@PathVariable UUID id, RedirectAttributes redirectAttributes) {
        return executeOperation(id, roomService::finishCleaning, "Cleaning finished successfully.", redirectAttributes);
    }

    /**
     * Starts maintenance for an available room from a CSRF-protected browser form.
     *
     * @param id room identifier
     * @param redirectAttributes attributes used to show post-redirect feedback
     * @return a detail redirect after the operation result is recorded
     */
    @PostMapping("/rooms/{id}/start-maintenance")
    @PreAuthorize("hasAuthority('PERM_MANAGE_ROOM')")
    public String startMaintenance(@PathVariable UUID id, RedirectAttributes redirectAttributes) {
        return executeOperation(
                id, roomService::startMaintenance, "Maintenance started successfully.", redirectAttributes);
    }

    /**
     * Finishes maintenance for a room from a CSRF-protected browser form.
     *
     * @param id room identifier
     * @param redirectAttributes attributes used to show post-redirect feedback
     * @return a detail redirect after the operation result is recorded
     */
    @PostMapping("/rooms/{id}/finish-maintenance")
    @PreAuthorize("hasAuthority('PERM_MANAGE_ROOM')")
    public String finishMaintenance(@PathVariable UUID id, RedirectAttributes redirectAttributes) {
        return executeOperation(
                id, roomService::finishMaintenance, "Maintenance finished successfully.", redirectAttributes);
    }

    /**
     * Marks an available room out of order from a CSRF-protected browser form.
     *
     * @param id room identifier
     * @param redirectAttributes attributes used to show post-redirect feedback
     * @return a detail redirect after the operation result is recorded
     */
    @PostMapping("/rooms/{id}/mark-out-of-order")
    @PreAuthorize("hasAuthority('PERM_MANAGE_ROOM')")
    public String markOutOfOrder(@PathVariable UUID id, RedirectAttributes redirectAttributes) {
        return executeOperation(
                id, roomService::markOutOfOrder, "Room marked out of order successfully.", redirectAttributes);
    }

    /**
     * Restores an out-of-order room to service from a CSRF-protected browser form.
     *
     * @param id room identifier
     * @param redirectAttributes attributes used to show post-redirect feedback
     * @return a detail redirect after the operation result is recorded
     */
    @PostMapping("/rooms/{id}/restore-to-service")
    @PreAuthorize("hasAuthority('PERM_MANAGE_ROOM')")
    public String restoreToService(@PathVariable UUID id, RedirectAttributes redirectAttributes) {
        return executeOperation(
                id, roomService::restoreToService, "Room restored to service successfully.", redirectAttributes);
    }

    /**
     * Executes one explicit Room status operation and redirects to the Room detail page after success.
     *
     * @param id room identifier
     * @param operation service operation to invoke
     * @param successMessage message shown after a successful operation
     * @param redirectAttributes attributes used to show post-redirect feedback
     * @return a detail redirect after the operation result is recorded
     */
    private String executeOperation(
            UUID id,
            Function<UUID, RoomResponse> operation,
            String successMessage,
            RedirectAttributes redirectAttributes) {
        try {
            operation.apply(id);
            redirectAttributes.addFlashAttribute("successMessage", successMessage);
            return "redirect:/rooms/" + id;
        } catch (ResponseStatusException exception) {
            redirectAttributes.addFlashAttribute("errorMessage", safeMessage(exception));
            return "redirect:/rooms/" + id;
        }
    }

    /**
     * Adds permission flags used by the existing shared sidebar.
     *
     * @param model model used to render a page
     * @param authentication current browser authentication
     */
    private void addAuthorizationAttributes(Model model, Authentication authentication) {
        model.addAttribute("canManageBooking", authentication.getAuthorities().stream()
                .anyMatch(authority -> "PERM_MANAGE_BOOKING".equals(authority.getAuthority())));
        model.addAttribute("canManageGuest", authentication.getAuthorities().stream()
                .anyMatch(authority -> "PERM_MANAGE_GUEST".equals(authority.getAuthority())));
        model.addAttribute("canViewReport", authentication.getAuthorities().stream()
                .anyMatch(authority -> "PERM_VIEW_REPORT".equals(authority.getAuthority())));
    }

    /**
     * Adds RoomType selections, form data, and shared authorization attributes for the room form.
     *
     * @param model model used to render the page
     * @param roomForm create or update form data
     * @param authentication current browser authentication
     */
    private void addFormAttributes(Model model, Object roomForm, Authentication authentication) {
        addAuthorizationAttributes(model, authentication);
        model.addAttribute("roomForm", roomForm);
        model.addAttribute("roomTypes", roomTypeQueryService.findAll());
    }

    /**
     * Builds an already URL-encoded query string containing only the currently populated Room
     * list filters, so pagination links can preserve every active filter without appending
     * blank query parameters for filters the user left empty.
     *
     * @param searchCriteria normalized Room list filters
     * @return the encoded {@code name=value&...} filter query string, or an empty string when
     *     no filter is active
     */
    private String filterQueryString(RoomSearchCriteria searchCriteria) {
        Map<String, String> filters = new LinkedHashMap<>();
        if (searchCriteria.getRoomNumber() != null) {
            filters.put("roomNumber", searchCriteria.getRoomNumber());
        }
        if (searchCriteria.getRoomTypeId() != null) {
            filters.put("roomTypeId", searchCriteria.getRoomTypeId().toString());
        }
        if (searchCriteria.getFloor() != null) {
            filters.put("floor", searchCriteria.getFloor());
        }
        if (searchCriteria.getStatus() != null) {
            filters.put("status", searchCriteria.getStatus().name());
        }
        if (filters.isEmpty()) {
            return "";
        }
        UriComponentsBuilder builder = UriComponentsBuilder.newInstance();
        filters.forEach(builder::queryParam);
        return builder.build().encode().getQuery();
    }

    /**
     * Adds presentation-only page-window bounds for the Room list paginator.
     *
     * @param model MVC model used by the Room list view
     * @param roomPage current server-side page metadata
     */
    private void addPaginationAttributes(Model model, Page<?> roomPage) {
        int totalPages = roomPage.getTotalPages();
        if (totalPages == 0) {
            return;
        }
        int lastPage = totalPages - 1;
        int startPage = Math.max(0, Math.min(roomPage.getNumber() - 1, lastPage - 2));
        int endPage = Math.min(lastPage, startPage + 2);
        model.addAttribute("paginationStartPage", startPage);
        model.addAttribute("paginationEndPage", endPage);
    }

    /**
     * Converts a room response into the mutable fields accepted by the update form.
     *
     * @param room room profile to populate the form
     * @return mutable room form data without status, active, or audit fields
     */
    private RoomUpdateRequest toUpdateRequest(RoomResponse room) {
        return new RoomUpdateRequest(room.roomNumber(), room.roomType().id(), room.floor());
    }

    /**
     * Selects a browser-safe message from a known service exception.
     *
     * @param exception exception raised by a Room Management operation
     * @return a safe message for the browser
     */
    private String safeMessage(ResponseStatusException exception) {
        return exception.getReason() == null
                ? HttpStatus.valueOf(exception.getStatusCode().value()).getReasonPhrase()
                : exception.getReason();
    }
}
