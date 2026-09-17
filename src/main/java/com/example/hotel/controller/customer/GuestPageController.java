package com.example.hotel.controller.customer;

import com.example.hotel.dto.customer.request.GuestCreateRequest;
import com.example.hotel.dto.customer.request.GuestSearchCriteria;
import com.example.hotel.dto.customer.request.GuestUpdateRequest;
import com.example.hotel.dto.customer.response.CountryCatalog;
import com.example.hotel.dto.customer.response.GuestDocumentResponse;
import com.example.hotel.dto.customer.response.GuestPassportImage;
import com.example.hotel.dto.customer.response.GuestResponse;
import com.example.hotel.exception.GuestDocumentValidationException;
import com.example.hotel.service.customer.GuestDocumentService;
import com.example.hotel.service.customer.GuestQueryService;
import com.example.hotel.service.customer.GuestService;
import jakarta.validation.Valid;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.core.io.Resource;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
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
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;
import org.springframework.web.util.UriComponentsBuilder;

/** Serves CSRF-protected Thymeleaf pages for authorized guest management. */
@Controller
public class GuestPageController {

    private final GuestService guestService;
    private final GuestQueryService guestQueryService;
    private final GuestDocumentService guestDocumentService;

    /**
     * Creates the guest page controller with the guest-management service.
     *
     * @param guestService service used to load and update guest data
     * @param guestQueryService service used to load paginated Guest list data
     * @param guestDocumentService service used to load private passport metadata and content
     */
    public GuestPageController(
            GuestService guestService,
            GuestQueryService guestQueryService,
            GuestDocumentService guestDocumentService) {
        this.guestService = guestService;
        this.guestQueryService = guestQueryService;
        this.guestDocumentService = guestDocumentService;
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
        addPassportAttributes(model, id);
        return "customer/detail";
    }

    /**
     * Returns one authorized Guest passport image for inline browser viewing.
     *
     * <p>Granted to MANAGE_GUEST (Guest management) and, separately, to CHECK_IN, since Staff
     * performing Check-in must be able to securely view a Guest's passport during the Check-in
     * workflow without receiving any Guest management/edit capability.</p>
     *
     * @param id guest identifier
     * @return the private image with its validated content type and safe inline header
     */
    @GetMapping("/guests/{id}/passport-image")
    @PreAuthorize("hasAnyAuthority('PERM_MANAGE_GUEST', 'PERM_CHECK_IN')")
    public ResponseEntity<Resource> passportImage(@PathVariable UUID id) {
        GuestPassportImage passportImage = guestDocumentService.loadPassport(id);
        return ResponseEntity.ok()
                .contentType(MediaType.parseMediaType(passportImage.contentType()))
                .header(
                        HttpHeaders.CONTENT_DISPOSITION,
                        ContentDisposition.inline()
                                .filename(passportImage.originalName(), StandardCharsets.UTF_8)
                                .build()
                                .toString())
                .body(passportImage.resource());
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
            @RequestParam(name = "passportImage", required = false) MultipartFile passportImage,
            Model model,
            Authentication authentication,
        RedirectAttributes redirectAttributes) {
        if (bindingResult.hasErrors()) {
            addFormAttributes(model, guestForm, authentication, unmappedSubmittedNationality(guestForm));
            return "customer/form";
        }
        try {
            GuestResponse guest = guestService.create(guestForm, passportImage);
            redirectAttributes.addFlashAttribute("successMessage", "Guest created successfully.");
            return "redirect:/guests/" + guest.id();
        } catch (GuestDocumentValidationException exception) {
            addFormAttributes(model, guestForm, authentication, null);
            model.addAttribute("passportError", exception.getMessage());
            return "customer/form";
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
        addPassportAttributes(model, id);
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
            @RequestParam(name = "passportImage", required = false) MultipartFile passportImage,
            Model model,
            Authentication authentication,
        RedirectAttributes redirectAttributes) {
        if (bindingResult.hasErrors()) {
            addFormAttributes(model, guestForm, authentication, unmappedSubmittedNationality(guestForm));
            model.addAttribute("guest", guestService.findById(id));
            addPassportAttributes(model, id);
            return "customer/form";
        }
        try {
            guestService.update(id, guestForm, passportImage);
            redirectAttributes.addFlashAttribute("successMessage", "Guest updated successfully.");
            return "redirect:/guests/" + id;
        } catch (GuestDocumentValidationException exception) {
            addFormAttributes(model, guestForm, authentication, null);
            model.addAttribute("guest", guestService.findById(id));
            addPassportAttributes(model, id);
            model.addAttribute("passportError", exception.getMessage());
            return "customer/form";
        } catch (ResponseStatusException exception) {
            addFormAttributes(model, guestForm, authentication, null);
            model.addAttribute("guest", guestService.findById(id));
            addPassportAttributes(model, id);
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
     * Adds safe passport metadata for Guest detail and edit templates without exposing storage
     * keys or physical paths.
     *
     * @param model model used to render the page
     * @param guestId Guest identifier
     */
    private void addPassportAttributes(Model model, UUID guestId) {
        GuestDocumentResponse passportDocument = guestDocumentService.findPassport(guestId).orElse(null);
        model.addAttribute("passportDocument", passportDocument);
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
     * Retains an invalid submitted nationality as a selected option while presenting its field
     * error, so a validation failure never silently discards browser-entered form data.
     *
     * @param guestForm invalid submitted create or update form
     * @return unsupported submitted nationality, or {@code null} when canonical or blank
     */
    private String unmappedSubmittedNationality(Object guestForm) {
        String nationality = switch (guestForm) {
            case GuestCreateRequest createRequest -> createRequest.nationality();
            case GuestUpdateRequest updateRequest -> updateRequest.nationality();
            default -> null;
        };
        return nationality != null && !nationality.isBlank() && !CountryCatalog.isCanonicalCountryName(nationality)
                ? nationality
                : null;
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
