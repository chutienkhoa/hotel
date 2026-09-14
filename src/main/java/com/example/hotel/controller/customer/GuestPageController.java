package com.example.hotel.controller.customer;

import com.example.hotel.dto.customer.request.GuestCreateRequest;
import com.example.hotel.dto.customer.request.GuestSearchCriteria;
import com.example.hotel.dto.customer.request.GuestUpdateRequest;
import com.example.hotel.dto.customer.response.CountryCatalog;
import com.example.hotel.dto.customer.response.GuestResponse;
import com.example.hotel.service.customer.GuestQueryService;
import com.example.hotel.service.customer.GuestService;
import jakarta.validation.Valid;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.data.domain.Page;
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

/** Serves CSRF-protected Thymeleaf pages for authorized guest management. */
@Controller
public class GuestPageController {

    private final GuestService guestService;
    private final GuestQueryService guestQueryService;

    /**
     * Creates the guest page controller with the guest-management service.
     *
     * @param guestService service used to load and update guest data
     * @param guestQueryService service used to load paginated Guest list data
     */
    public GuestPageController(GuestService guestService, GuestQueryService guestQueryService) {
        this.guestService = guestService;
        this.guestQueryService = guestQueryService;
    }

    /**
     * Displays a searchable, paginated Guest list for guest-management users.
     *
     * @param model model used to render the page
     * @param authentication current browser authentication
     * @return the customer list template
     */
    @GetMapping("/guests")
    @PreAuthorize("hasAuthority('PERM_MANAGE_GUEST')")
    public String list(
            @ModelAttribute("searchCriteria") GuestSearchCriteria searchCriteria,
            @RequestParam(required = false) Integer page,
            Model model,
            Authentication authentication) {
        addAuthorizationAttributes(model, authentication);
        searchCriteria.normalize();
        Page<?> guestPage = guestQueryService.findPage(searchCriteria, page == null ? 0 : page);
        model.addAttribute("guestPage", guestPage);
        model.addAttribute("countries", CountryCatalog.countries());
        model.addAttribute("filterQueryString", filterQueryString(searchCriteria));
        addPaginationAttributes(model, guestPage);
        return "customer/list";
    }

    /**
     * Displays one guest profile.
     *
     * @param id guest identifier
     * @param model model used to render the page
     * @param authentication current browser authentication
     * @return the customer detail template
     */
    @GetMapping("/guests/{id}")
    @PreAuthorize("hasAuthority('PERM_MANAGE_GUEST')")
    public String detail(@PathVariable UUID id, Model model, Authentication authentication) {
        addAuthorizationAttributes(model, authentication);
        model.addAttribute("guest", guestService.findById(id));
        return "customer/detail";
    }

    /**
     * Displays an empty guest creation form.
     *
     * @param model model used to render the page
     * @param authentication current browser authentication
     * @return the customer form template
     */
    @GetMapping("/guests/new")
    @PreAuthorize("hasAuthority('PERM_MANAGE_GUEST')")
    public String createForm(Model model, Authentication authentication) {
        addFormAttributes(
                model, new GuestCreateRequest(null, null, null, null, null, null, null), authentication, null);
        return "customer/form";
    }

    /**
     * Creates a guest from a browser form and redirects to its detail page after success.
     *
     * @param guestForm validated form data
     * @param bindingResult structural validation result
     * @param model model used to redisplay invalid input
     * @param authentication current browser authentication
     * @param redirectAttributes attributes used to show post-redirect feedback
     * @return a detail redirect or the form template after validation failure
     */
    @PostMapping("/guests")
    @PreAuthorize("hasAuthority('PERM_MANAGE_GUEST')")
    public String create(
            @Valid @ModelAttribute("guestForm") GuestCreateRequest guestForm,
            BindingResult bindingResult,
            Model model,
            Authentication authentication,
            RedirectAttributes redirectAttributes) {
        if (bindingResult.hasErrors()) {
            addFormAttributes(model, guestForm, authentication, null);
            return "customer/form";
        }
        try {
            GuestResponse guest = guestService.create(guestForm);
            redirectAttributes.addFlashAttribute("successMessage", "Guest created successfully.");
            return "redirect:/guests/" + guest.id();
        } catch (ResponseStatusException exception) {
            addFormAttributes(model, guestForm, authentication, null);
            model.addAttribute("errorMessage", safeMessage(exception));
            return "customer/form";
        }
    }

    /**
     * Displays a pre-populated guest update form.
     *
     * @param id guest identifier
     * @param model model used to render the page
     * @param authentication current browser authentication
     * @return the customer form template
     */
    @GetMapping("/guests/{id}/edit")
    @PreAuthorize("hasAuthority('PERM_MANAGE_GUEST')")
    public String updateForm(@PathVariable UUID id, Model model, Authentication authentication) {
        GuestResponse guest = guestService.findById(id);
        NationalitySelection nationalitySelection = resolveNationalitySelection(guest.nationality());
        addFormAttributes(
                model,
                toUpdateRequest(guest, nationalitySelection.selectedValue()),
                authentication,
                nationalitySelection.unmappedValue());
        model.addAttribute("guest", guest);
        return "customer/form";
    }

    /**
     * Updates a guest from a browser form and redirects to its detail page after success.
     *
     * @param id guest identifier
     * @param guestForm validated form data
     * @param bindingResult structural validation result
     * @param model model used to redisplay invalid input
     * @param authentication current browser authentication
     * @param redirectAttributes attributes used to show post-redirect feedback
     * @return a detail redirect or the form template after validation failure
     */
    @PostMapping("/guests/{id}")
    @PreAuthorize("hasAuthority('PERM_MANAGE_GUEST')")
    public String update(
            @PathVariable UUID id,
            @Valid @ModelAttribute("guestForm") GuestUpdateRequest guestForm,
            BindingResult bindingResult,
            Model model,
            Authentication authentication,
            RedirectAttributes redirectAttributes) {
        if (bindingResult.hasErrors()) {
            addFormAttributes(model, guestForm, authentication, null);
            model.addAttribute("guest", guestService.findById(id));
            return "customer/form";
        }
        try {
            guestService.update(id, guestForm);
            redirectAttributes.addFlashAttribute("successMessage", "Guest updated successfully.");
            return "redirect:/guests/" + id;
        } catch (ResponseStatusException exception) {
            addFormAttributes(model, guestForm, authentication, null);
            model.addAttribute("guest", guestService.findById(id));
            model.addAttribute("errorMessage", safeMessage(exception));
            return "customer/form";
        }
    }

    /**
     * Adds the guest-management permission flag used by the shared sidebar.
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
     * Adds form data and shared authorization attributes needed by the guest form template.
     *
     * @param model model used to render a page
     * @param guestForm create or update form data
     * @param authentication current browser authentication
     * @param unmappedNationality the Guest's current nationality when it cannot be safely
     *     resolved to a canonical country, so the Edit dropdown can preserve it verbatim
     *     instead of silently discarding it; {@code null} for Create, a resolved value, or an
     *     absent value
     */
    private void addFormAttributes(
            Model model, Object guestForm, Authentication authentication, String unmappedNationality) {
        addAuthorizationAttributes(model, authentication);
        model.addAttribute("guestForm", guestForm);
        model.addAttribute("countries", CountryCatalog.countries());
        model.addAttribute("unmappedNationality", unmappedNationality);
    }

    /**
     * Resolves a Guest's stored nationality to the value the Edit dropdown should preselect.
     *
     * <p>A canonical country name or a known legacy demonym resolves to its canonical country
     * name. An unmapped value (unknown legacy text) is preserved verbatim as both the
     * preselected value and the value the template must render as an extra dropdown option, so
     * an Edit submission that does not touch the field cannot silently discard it.</p>
     *
     * @param storedNationality the Guest's currently stored nationality text
     * @return the dropdown preselection and, when applicable, the unmapped value to preserve
     */
    private NationalitySelection resolveNationalitySelection(String storedNationality) {
        if (storedNationality == null || storedNationality.isBlank()) {
            return new NationalitySelection(null, null);
        }
        return CountryCatalog.canonicalNameFor(storedNationality)
                .map(canonicalName -> new NationalitySelection(canonicalName, null))
                .orElseGet(() -> new NationalitySelection(storedNationality, storedNationality));
    }

    /**
     * Carries the Edit dropdown preselection derived from a Guest's stored nationality.
     *
     * @param selectedValue the value the dropdown should preselect
     * @param unmappedValue the original stored value, when it could not be safely resolved to a
     *     canonical country and must be preserved as an extra dropdown option
     */
    private record NationalitySelection(String selectedValue, String unmappedValue) {}

    /**
     * Builds an already URL-encoded query string containing only the currently populated Guest
     * list filters, so pagination links can preserve every active filter without appending
     * blank query parameters for filters the user left empty.
     *
     * @param searchCriteria normalized Guest list filters
     * @return the encoded {@code name=value&...} filter query string, or an empty string when
     *     no filter is active
     */
    private String filterQueryString(GuestSearchCriteria searchCriteria) {
        Map<String, String> filters = new LinkedHashMap<>();
        putIfPresent(filters, "guestCode", searchCriteria.getGuestCode());
        putIfPresent(filters, "firstName", searchCriteria.getFirstName());
        putIfPresent(filters, "lastName", searchCriteria.getLastName());
        putIfPresent(filters, "email", searchCriteria.getEmail());
        putIfPresent(filters, "nationality", searchCriteria.getNationality());
        if (filters.isEmpty()) {
            return "";
        }
        UriComponentsBuilder builder = UriComponentsBuilder.newInstance();
        filters.forEach(builder::queryParam);
        return builder.build().encode().getQuery();
    }

    /**
     * Adds one filter parameter only when it was actually supplied.
     *
     * @param filters filter map being built for the pagination filter query string
     * @param name filter parameter name
     * @param value normalized optional filter value
     */
    private void putIfPresent(Map<String, String> filters, String name, String value) {
        if (value != null) {
            filters.put(name, value);
        }
    }

    /**
     * Adds presentation-only page-window bounds for the Guest list paginator.
     *
     * @param model MVC model used by the Guest list view
     * @param guestPage current server-side page metadata
     */
    private void addPaginationAttributes(Model model, Page<?> guestPage) {
        int totalPages = guestPage.getTotalPages();
        if (totalPages == 0) {
            return;
        }
        int lastPage = totalPages - 1;
        int startPage = Math.max(0, Math.min(guestPage.getNumber() - 1, lastPage - 2));
        int endPage = Math.min(lastPage, startPage + 2);
        model.addAttribute("paginationStartPage", startPage);
        model.addAttribute("paginationEndPage", endPage);
    }

    /**
     * Converts a guest response into the mutable fields accepted by the update form.
     *
     * @param guest guest profile to populate the form
     * @param nationalitySelection the resolved nationality dropdown preselection
     * @return mutable guest form data without the immutable code
     */
    private GuestUpdateRequest toUpdateRequest(GuestResponse guest, String nationalitySelection) {
        return new GuestUpdateRequest(
                guest.firstName(),
                guest.lastName(),
                guest.email(),
                guest.phone(),
                nationalitySelection,
                guest.dateOfBirth(),
                guest.address());
    }

    /**
     * Selects a browser-safe message from a known service exception.
     *
     * @param exception exception raised by a guest operation
     * @return a safe message for the browser
     */
    private String safeMessage(ResponseStatusException exception) {
        return exception.getReason() == null
                ? HttpStatus.valueOf(exception.getStatusCode().value()).getReasonPhrase()
                : exception.getReason();
    }
}
