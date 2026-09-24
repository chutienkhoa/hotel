package com.example.hotel.controller.customer;

import com.example.hotel.common.TableSorts;
import com.example.hotel.common.PaginationSupport;
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
import java.util.List;
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

/** Serves CSRF-protected Thymeleaf pages for authorized guest management. */
@Controller
public class GuestPageController {

    /**
     * Authorization for creating a Guest record, which is deliberately wider than the rest of Guest Management.
     *
     * <p>An authorized operational workflow must not dead-end because the Guest does not exist yet: Reservation
     * creation runs under {@code MANAGE_BOOKING}, and Walk-in / "OTA Booking Not Entered" run under
     * {@code CHECK_IN} (spec 23.1 B and C, which both require Staff to "select an existing Guest or create a new
     * one"). Those operational permissions therefore authorize creating the Guest record itself. This follows the
     * boundary already approved for passport image reads, which {@code CHECK_IN} may perform without being granted
     * Guest management.</p>
     *
     * <p>It grants nothing else: the Guest list, detail, edit and passport-document administration all remain
     * {@code MANAGE_GUEST}-only, and the create endpoint drops submitted passport files for a caller without
     * {@code MANAGE_GUEST}.</p>
     */
    private static final String OPERATIONAL_GUEST_CREATION =
            "hasAnyAuthority('PERM_MANAGE_GUEST', 'PERM_MANAGE_BOOKING', 'PERM_CHECK_IN')";

    /** The operational workflows that may send a user to Guest creation and receive them back afterwards. */
    private static final List<String> OPERATIONAL_RETURN_TARGETS =
            List.of("/reservations/new", "/check-in/walk-in", "/check-in/ota-entry");

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
            @RequestParam(required = false) String page,
            Model model,
            Authentication authentication) {
        addAuthorizationAttributes(model, authentication);
        searchCriteria.normalize();
        int requestedPage = PaginationSupport.parsePage(page);
        Page<?> guestPage = guestQueryService.findPage(searchCriteria, requestedPage);
        String sortKey = TableSorts.GUEST.key(searchCriteria.getSort(), searchCriteria.getDir());
        String sortDir = TableSorts.GUEST.activeDirection(searchCriteria.getSort(), searchCriteria.getDir());
        String redirect = PaginationSupport.redirectWhenOutOfRange(
                guestPage, requestedPage, "/guests", filters(searchCriteria), sortKey, sortDir);
        if (redirect != null) {
            return redirect;
        }
        model.addAttribute("guestPage", guestPage);
        model.addAttribute("countries", CountryCatalog.countries());
        PaginationSupport.populate(model, guestPage, "/guests", filters(searchCriteria), sortKey, sortDir);
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
     * Returns one specific authorized Guest passport image for inline browser viewing.
     *
     * <p>Granted to MANAGE_GUEST (Guest management) and, separately, to CHECK_IN, since Staff
     * performing Check-in must be able to securely view a Guest's passport during the Check-in
     * workflow without receiving any Guest management/edit capability. The requested document
     * must belong to the requested Guest and be a passport image, so a Guest A URL combined with
     * a Guest B document identifier can never resolve.</p>
     *
     * @param guestId guest identifier the document must belong to
     * @param documentId requested passport document identifier
     * @return the private image with its validated content type and safe inline header
     */
    @GetMapping("/guests/{guestId}/documents/{documentId}/passport")
    @PreAuthorize("hasAnyAuthority('PERM_MANAGE_GUEST', 'PERM_CHECK_IN')")
    public ResponseEntity<Resource> passportImage(@PathVariable UUID guestId, @PathVariable UUID documentId) {
        GuestPassportImage passportImage = guestDocumentService.loadPassport(guestId, documentId);
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
     * Removes one specific passport image belonging to a Guest and redirects back to its Edit
     * form. This is a Guest-management mutation and removes only the identified document; every
     * other passport image owned by the Guest remains untouched.
     *
     * @param guestId guest identifier the document must belong to
     * @param documentId passport document identifier to remove
     * @param redirectAttributes attributes used to show post-redirect feedback
     * @return a redirect back to the Guest's Edit form
     */
    @PostMapping("/guests/{guestId}/documents/{documentId}/remove")
    @PreAuthorize("hasAuthority('PERM_MANAGE_GUEST')")
    public String removePassportImage(
            @PathVariable UUID guestId, @PathVariable UUID documentId, RedirectAttributes redirectAttributes) {
        guestDocumentService.removePassportImage(guestId, documentId);
        redirectAttributes.addFlashAttribute("successMessage", "Passport image removed successfully.");
        return "redirect:/guests/" + guestId + "/edit";
    }

    /**
     * Displays an empty guest creation form.
     *
     * @param model model used to render the page
     * @param authentication current browser authentication
     * @return the customer form template
     */
    @GetMapping("/guests/new")
    @PreAuthorize(OPERATIONAL_GUEST_CREATION)
    public String createForm(
            @RequestParam(name = "returnTo", required = false) String returnTo,
            Model model,
            Authentication authentication) {
        addFormAttributes(
                model, new GuestCreateRequest(null, null, null, null, null, null, null), authentication, null);
        model.addAttribute("returnTo", allowedReturnTarget(returnTo));
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
    @PreAuthorize(OPERATIONAL_GUEST_CREATION)
    public String create(
            @Valid @ModelAttribute("guestForm") GuestCreateRequest guestForm,
            BindingResult bindingResult,
            @RequestParam(name = "passportImages", required = false) List<MultipartFile> passportImages,
            @RequestParam(name = "returnTo", required = false) String returnTo,
            Model model,
            Authentication authentication,
        RedirectAttributes redirectAttributes) {
        String returnTarget = allowedReturnTarget(returnTo);
        // Passport documents stay Guest Management territory: a user holding only an operational booking/check-in
        // permission may create the Guest record itself, but never administers its passport documents. Submitted
        // files are dropped rather than silently accepted, and the form hides the upload control for that user.
        List<MultipartFile> acceptedPassportImages =
                hasAuthority(authentication, "PERM_MANAGE_GUEST") ? passportImages : null;
        if (bindingResult.hasErrors()) {
            addFormAttributes(model, guestForm, authentication, unmappedSubmittedNationality(guestForm));
            model.addAttribute("returnTo", returnTarget);
            return "customer/form";
        }
        try {
            GuestResponse guest = guestService.create(guestForm, acceptedPassportImages);
            redirectAttributes.addFlashAttribute("successMessage", "Guest created successfully.");
            if (returnTarget != null) {
                redirectAttributes.addFlashAttribute("createdGuestId", guest.id());
                return "redirect:" + returnTarget;
            }
            return "redirect:/guests/" + guest.id();
        } catch (GuestDocumentValidationException exception) {
            addFormAttributes(model, guestForm, authentication, null);
            model.addAttribute("returnTo", returnTarget);
            model.addAttribute("passportError", exception.getMessage());
            return "customer/form";
        } catch (ResponseStatusException exception) {
            addFormAttributes(model, guestForm, authentication, null);
            model.addAttribute("returnTo", returnTarget);
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
            @RequestParam(name = "passportImages", required = false) List<MultipartFile> passportImages,
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
            guestService.update(id, guestForm, passportImages);
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
        model.addAttribute("canManageBooking", hasAuthority(authentication, "PERM_MANAGE_BOOKING"));
        model.addAttribute("canManageGuest", hasAuthority(authentication, "PERM_MANAGE_GUEST"));
        model.addAttribute("canViewReport", hasAuthority(authentication, "PERM_VIEW_REPORT"));
    }

    /**
     * Tells whether the current authentication carries a backend authority.
     *
     * @param authentication current browser authentication
     * @param authority the required authority
     * @return {@code true} when present
     */
    private boolean hasAuthority(Authentication authentication, String authority) {
        return authentication != null && authentication.getAuthorities().stream()
                .anyMatch(granted -> authority.equals(granted.getAuthority()));
    }

    /**
     * Resolves the operational workflow a Guest creation was started from, so an operational user is returned to it
     * instead of the {@code MANAGE_GUEST}-only Guest detail page they may not open.
     *
     * <p>Only the known operational origins are accepted. An unknown, absent or externally-supplied value resolves to
     * {@code null}, which restores the default Guest Management behavior; the target is never echoed back from the
     * request, so this cannot be used as an open redirect.</p>
     *
     * @param returnTo the submitted return target
     * @return the matching allowed target, or {@code null}
     */
    private String allowedReturnTarget(String returnTo) {
        // List.of(...) rejects a null argument to contains(), and no return target is the normal Guest Management case.
        return returnTo != null && OPERATIONAL_RETURN_TARGETS.contains(returnTo) ? returnTo : null;
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
     * Adds safe passport metadata for every passport image owned by a Guest to detail and edit
     * templates, without exposing storage keys or physical paths.
     *
     * @param model model used to render the page
     * @param guestId Guest identifier
     */
    private void addPassportAttributes(Model model, UUID guestId) {
        List<GuestDocumentResponse> passportDocuments = guestDocumentService.findPassports(guestId);
        model.addAttribute("passportDocuments", passportDocuments);
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
    private Map<String, String> filters(GuestSearchCriteria searchCriteria) {
        Map<String, String> filters = new LinkedHashMap<>();
        putIfPresent(filters, "guestCode", searchCriteria.getGuestCode());
        putIfPresent(filters, "firstName", searchCriteria.getFirstName());
        putIfPresent(filters, "lastName", searchCriteria.getLastName());
        putIfPresent(filters, "email", searchCriteria.getEmail());
        putIfPresent(filters, "nationality", searchCriteria.getNationality());
        return filters;
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
