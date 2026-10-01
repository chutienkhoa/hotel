package com.example.hotel.controller.customer;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.example.hotel.controller.common.NavigationModelAdvice;
import com.example.hotel.dto.customer.response.GuestLookupResponse;
import com.example.hotel.dto.customer.response.GuestListResponse;
import com.example.hotel.dto.customer.response.GuestDocumentResponse;
import com.example.hotel.dto.customer.response.GuestPassportImage;
import com.example.hotel.dto.customer.response.GuestResponse;
import com.example.hotel.exception.GuestDocumentValidationException;
import com.example.hotel.security.JwtService;
import com.example.hotel.service.customer.GuestQueryService;
import com.example.hotel.service.customer.GuestService;
import com.example.hotel.service.customer.GuestDocumentService;
import java.util.List;
import java.util.UUID;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

/** Verifies the approved authorization boundary between Guest Management and reservation lookup. */
@WebMvcTest({GuestController.class, GuestLookupController.class, GuestPageController.class})
@Import({GuestAuthorizationTest.MethodSecurityTestConfiguration.class, NavigationModelAdvice.class})
class GuestAuthorizationTest {

    private static final UUID GUEST_ID = UUID.fromString("11111111-1111-1111-1111-111111111111");
    private static final UUID DOCUMENT_ID = UUID.fromString("33333333-3333-3333-3333-333333333333");

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private GuestService guestService;

    @MockitoBean
    private GuestQueryService guestQueryService;

    @MockitoBean
    private GuestDocumentService guestDocumentService;

    @MockitoBean
    private JwtService jwtService;

    /**
     * Confirms ADMIN and MANAGER can access the Guest Management list and detail operations.
     *
     * @param username representative administrative user
     * @throws Exception if MockMvc cannot perform the requests
     */
    @ParameterizedTest
    @MethodSource("guestManagers")
    void shouldAllowAdministrativeRolesToAccessGuestManagement(String username) throws Exception {
        when(guestService.findAll()).thenReturn(List.of());
        when(guestService.findById(GUEST_ID)).thenReturn(guestResponse());

        mockMvc.perform(get("/api/guests").with(user(username).authorities(manageGuestAuthority())))
                .andExpect(status().isOk());
        mockMvc.perform(get("/api/guests/{id}", GUEST_ID)
                        .with(user(username).authorities(manageGuestAuthority())))
                .andExpect(status().isOk());
    }

    /**
     * Confirms STAFF cannot access Guest Management with reservation view and check-in permissions.
     *
     * @throws Exception if MockMvc cannot perform the requests
     */
    @Test
    void shouldRejectStaffFromGuestManagement() throws Exception {
        mockMvc.perform(get("/api/guests").with(user("staff").authorities(staffAuthorities())))
                .andExpect(status().isForbidden());
        mockMvc.perform(get("/api/guests/{id}", GUEST_ID)
                        .with(user("staff").authorities(staffAuthorities())))
                .andExpect(status().isForbidden());
    }

    /**
     * Confirms the approved narrow operational boundary: a user holding only an operational booking/check-in
     * permission may open and submit Guest creation, so an authorized Walk-in / OTA-entry / Reservation-create
     * workflow no longer dead-ends on a missing Guest.
     *
     * @param username representative operational user
     * @param authority the operational permission that owns the workflow
     * @param returnTo the workflow the user came from
     * @throws Exception if MockMvc cannot perform the requests
     */
    @ParameterizedTest
    @MethodSource("operationalGuestCreators")
    void shouldAllowOperationalGuestCreation(String username, String authority, String returnTo) throws Exception {
        when(guestService.create(any(), any())).thenReturn(guestResponse());

        mockMvc.perform(get("/guests/new").param("returnTo", returnTo)
                        .with(user(username).authorities(new SimpleGrantedAuthority(authority))))
                .andExpect(status().isOk());
        mockMvc.perform(post("/guests")
                        .with(user(username).authorities(new SimpleGrantedAuthority(authority)))
                        .with(csrf())
                        .param("firstName", "Ann")
                        .param("lastName", "Lee")
                        .param("nationality", "Vietnam")
                        .param("returnTo", returnTo))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl(returnTo));
    }

    /**
     * Confirms the operational boundary grants creation ONLY: Guest list, detail, edit and passport-document
     * administration all stay {@code MANAGE_GUEST}-only.
     *
     * @throws Exception if MockMvc cannot perform the requests
     */
    @Test
    void shouldNotGrantAnyOtherGuestCapabilityToOperationalUsers() throws Exception {
        var checkIn = List.of(new SimpleGrantedAuthority("PERM_CHECK_IN"));

        mockMvc.perform(get("/guests").with(user("staff").authorities(checkIn)))
                .andExpect(status().isForbidden());
        mockMvc.perform(get("/guests/{id}", GUEST_ID).with(user("staff").authorities(checkIn)))
                .andExpect(status().isForbidden());
        mockMvc.perform(get("/guests/{id}/edit", GUEST_ID).with(user("staff").authorities(checkIn)))
                .andExpect(status().isForbidden());
        mockMvc.perform(post("/guests/{id}", GUEST_ID).with(user("staff").authorities(checkIn)).with(csrf()))
                .andExpect(status().isForbidden());
        mockMvc.perform(post("/guests/{guestId}/documents/{documentId}/remove", GUEST_ID, DOCUMENT_ID)
                        .with(user("staff").authorities(checkIn)).with(csrf()))
                .andExpect(status().isForbidden());
        mockMvc.perform(post("/api/guests").with(user("staff").authorities(checkIn)).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"firstName\":\"Ann\",\"lastName\":\"Lee\",\"nationality\":\"Vietnam\"}"))
                .andExpect(status().isForbidden());
    }

    /**
     * Confirms a user with neither an operational booking/check-in permission nor {@code MANAGE_GUEST} still cannot
     * create a Guest.
     *
     * @throws Exception if MockMvc cannot perform the requests
     */
    @Test
    void shouldRejectGuestCreationWithoutAnyAuthorizingPermission() throws Exception {
        mockMvc.perform(get("/guests/new").with(user("viewer").authorities(unrelatedAuthorities())))
                .andExpect(status().isForbidden());
        mockMvc.perform(post("/guests").with(user("viewer").authorities(unrelatedAuthorities())).with(csrf())
                        .param("firstName", "Ann")
                        .param("lastName", "Lee")
                        .param("nationality", "Vietnam"))
                .andExpect(status().isForbidden());
    }

    /**
     * Confirms passport documents remain Guest Management territory: an operational creator never sees the upload
     * control, and any submitted file is dropped rather than stored.
     *
     * @throws Exception if MockMvc cannot perform the requests
     */
    @Test
    void shouldNotAcceptPassportUploadsFromAnOperationalGuestCreator() throws Exception {
        when(guestService.create(any(), any())).thenReturn(guestResponse());
        var checkIn = List.of(new SimpleGrantedAuthority("PERM_CHECK_IN"));

        mockMvc.perform(get("/guests/new").param("returnTo", "/check-in/walk-in")
                        .with(user("staff").authorities(checkIn)))
                .andExpect(status().isOk())
                .andExpect(content().string(not(containsString("id=\"passportImages\""))));

        mockMvc.perform(multipart("/guests")
                        .file(new MockMultipartFile("passportImages", "p.jpg", MediaType.IMAGE_JPEG_VALUE, new byte[] {1}))
                        .with(user("staff").authorities(checkIn))
                        .with(csrf())
                        .param("firstName", "Ann")
                        .param("lastName", "Lee")
                        .param("nationality", "Vietnam")
                        .param("returnTo", "/check-in/walk-in"))
                .andExpect(status().is3xxRedirection());

        verify(guestService).create(any(), isNull());
    }

    /**
     * Confirms the default Guest Management behavior is untouched for {@code MANAGE_GUEST}: creation without an
     * operational origin still lands on the Guest detail page, and the upload control is still rendered.
     *
     * @throws Exception if MockMvc cannot perform the requests
     */
    @Test
    void shouldPreserveGuestManagementCreationBehaviourForManageGuest() throws Exception {
        when(guestService.create(any(), any())).thenReturn(guestResponse());

        mockMvc.perform(get("/guests/new").with(user("admin").authorities(manageGuestAuthority())))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("id=\"passportImages\"")));
        mockMvc.perform(post("/guests")
                        .with(user("admin").authorities(manageGuestAuthority()))
                        .with(csrf())
                        .param("firstName", "Ann")
                        .param("lastName", "Lee")
                        .param("nationality", "Vietnam"))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/guests/" + GUEST_ID));
    }

    /**
     * Confirms an unknown return target cannot be used as an open redirect: it is ignored and the default Guest
     * Management destination is used instead.
     *
     * @throws Exception if MockMvc cannot perform the requests
     */
    @Test
    void shouldIgnoreAnUnknownReturnTarget() throws Exception {
        when(guestService.create(any(), any())).thenReturn(guestResponse());

        mockMvc.perform(post("/guests")
                        .with(user("admin").authorities(manageGuestAuthority()))
                        .with(csrf())
                        .param("firstName", "Ann")
                        .param("lastName", "Lee")
                        .param("nationality", "Vietnam")
                        .param("returnTo", "https://evil.example.com/steal"))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/guests/" + GUEST_ID));
    }

    /** Confirms the shared Guest form grid is used for both creation and editing. */
    @Test
    void shouldRenderGuestFormGridForCreationAndEditing() throws Exception {
        mockMvc.perform(get("/guests/new").with(user("admin").authorities(manageGuestAuthority())))
                .andExpect(status().isOk())
                .andExpect(content().string(org.hamcrest.Matchers.containsString(
                        "class=\"form-grid guest-form-grid\"")))
                .andExpect(content().string(org.hamcrest.Matchers.containsString(
                        "class=\"form-field guest-form-address\"")))
                .andExpect(content().string(org.hamcrest.Matchers.containsString(
                        "class=\"js-date-picker\"")))
                .andExpect(content().string(org.hamcrest.Matchers.containsString(
                        "data-confirm-title=\"Create guest\"")))
                .andExpect(content().string(org.hamcrest.Matchers.containsString(
                        "data-confirm-label=\"Create guest\"")))
                .andExpect(content().string(org.hamcrest.Matchers.containsString(
                        "data-confirm-severity=\"NORMAL\"")));

        when(guestService.findById(GUEST_ID)).thenReturn(guestResponse());
        mockMvc.perform(get("/guests/{id}/edit", GUEST_ID)
                        .with(user("admin").authorities(manageGuestAuthority())))
                .andExpect(status().isOk())
                .andExpect(content().string(org.hamcrest.Matchers.containsString(
                        "class=\"form-grid guest-form-grid\"")))
                .andExpect(content().string(org.hamcrest.Matchers.containsString(
                        "data-confirm-title=\"Update guest\"")));
    }

    /** Confirms the Create form renders Nationality as a placeholder-led country select, not free text. */
    @Test
    void shouldRenderNationalityAsCountrySelectOnCreateForm() throws Exception {
        mockMvc.perform(get("/guests/new").with(user("admin").authorities(manageGuestAuthority())))
                .andExpect(status().isOk())
                .andExpect(content().string(org.hamcrest.Matchers.containsString(
                        "<select id=\"nationality\" required name=\"nationality\">")))
                .andExpect(content().string(org.hamcrest.Matchers.not(org.hamcrest.Matchers.containsString(
                        "id=\"nationality\" maxlength=\"100\""))))
                .andExpect(content().string(org.hamcrest.Matchers.containsString(
                        "<option value=\"\">Select nationality</option>")))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("🇯🇵 Japan")))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("🇻🇳 Vietnam")));
    }

    /** Confirms the Edit form preselects the Guest's existing canonical nationality. */
    @Test
    void shouldPreselectCanonicalNationalityOnEditForm() throws Exception {
        when(guestService.findById(GUEST_ID)).thenReturn(guestResponse("Japan"));

        mockMvc.perform(get("/guests/{id}/edit", GUEST_ID)
                        .with(user("admin").authorities(manageGuestAuthority())))
                .andExpect(status().isOk())
                .andExpect(content().string(org.hamcrest.Matchers.containsString(
                        "value=\"Japan\" selected=\"selected\"")));
    }

    /** Confirms a known legacy nationality safely preselects its canonical country on the Edit form. */
    @Test
    void shouldMapKnownLegacyNationalityToCanonicalSelectionOnEditForm() throws Exception {
        when(guestService.findById(GUEST_ID)).thenReturn(guestResponse("Japanese"));

        mockMvc.perform(get("/guests/{id}/edit", GUEST_ID)
                        .with(user("admin").authorities(manageGuestAuthority())))
                .andExpect(status().isOk())
                .andExpect(content().string(org.hamcrest.Matchers.containsString(
                        "value=\"Japan\" selected=\"selected\"")))
                .andExpect(content().string(org.hamcrest.Matchers.not(org.hamcrest.Matchers.containsString(
                        ">Japanese</option>"))));
    }

    /** Confirms an unmappable legacy nationality is preserved as a selected option, not silently discarded. */
    @Test
    void shouldPreserveUnknownLegacyNationalityOnEditForm() throws Exception {
        when(guestService.findById(GUEST_ID)).thenReturn(guestResponse("Atlantean"));

        mockMvc.perform(get("/guests/{id}/edit", GUEST_ID)
                        .with(user("admin").authorities(manageGuestAuthority())))
                .andExpect(status().isOk())
                .andExpect(content().string(org.hamcrest.Matchers.containsString(
                        "value=\"Atlantean\" selected=\"selected\"")))
                .andExpect(content().string(org.hamcrest.Matchers.containsString(">Atlantean</option>")));
    }

    /** Confirms creating a guest persists the canonical country name selected from the dropdown. */
    @Test
    void shouldSubmitCanonicalCountryNameWhenCreatingGuest() throws Exception {
        when(guestService.create(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any()))
                .thenReturn(guestResponse("Japan"));
        org.mockito.ArgumentCaptor<com.example.hotel.dto.customer.request.GuestCreateRequest> captor =
                org.mockito.ArgumentCaptor.forClass(com.example.hotel.dto.customer.request.GuestCreateRequest.class);

        mockMvc.perform(post("/guests")
                        .param("firstName", "Khoa")
                        .param("lastName", "Nguyen")
                        .param("nationality", "Japan")
                        .with(user("admin").authorities(manageGuestAuthority()))
                        .with(csrf()))
                .andExpect(status().is3xxRedirection());

        verify(guestService).create(captor.capture(), org.mockito.ArgumentMatchers.any());
        org.junit.jupiter.api.Assertions.assertEquals("Japan", captor.getValue().nationality());
    }

    /** Confirms missing or manipulated required Guest values remain on the form with field errors. */
    @Test
    void shouldRejectMissingAndUnsupportedRequiredGuestFields() throws Exception {
        mockMvc.perform(post("/guests")
                        .param("firstName", "   ")
                        .param("lastName", "")
                        .param("nationality", "Atlantis")
                        .with(user("admin").authorities(manageGuestAuthority()))
                        .with(csrf()))
                .andExpect(status().isOk())
                .andExpect(content().string(org.hamcrest.Matchers.containsString("First name is required.")))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("Last name is required.")))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("Nationality is not supported.")))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("value=\"Atlantis\"")));

        org.mockito.Mockito.verifyNoInteractions(guestService);
    }

    /** Confirms direct API callers cannot bypass canonical nationality validation. */
    @Test
    void shouldRejectManipulatedNationalityThroughGuestApi() throws Exception {
        mockMvc.perform(post("/api/guests")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"firstName\":\"First\",\"lastName\":\"Last\",\"nationality\":\"Atlantis\"}")
                        .with(user("admin").authorities(manageGuestAuthority()))
                        .with(csrf()))
                .andExpect(status().isBadRequest());
    }

    /** Confirms the forms mark required fields and expose the optional private-image upload affordance. */
    @Test
    void shouldRenderRequiredGuestFieldsAndOptionalPassportUpload() throws Exception {
        mockMvc.perform(get("/guests/new").with(user("admin").authorities(manageGuestAuthority())))
                .andExpect(status().isOk())
                .andExpect(content().string(org.hamcrest.Matchers.containsString("First name *")))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("Last name *")))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("Nationality *")))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("enctype=\"multipart/form-data\"")))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("name=\"passportImages\"")))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("multiple=\"multiple\"")))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("image/jpeg,image/png")));
    }

    /**
     * Confirms an oversized Create Guest passport upload returns to the form with a friendly
     * field-level error, preserved input, and no redirect (no raw 413).
     *
     * @throws Exception if MockMvc cannot perform the request
     */
    @Test
    void shouldReturnCreateFormWithFriendlyPassportErrorAndPreservedFieldsWhenUploadTooLarge() throws Exception {
        when(guestService.create(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any()))
                .thenThrow(new GuestDocumentValidationException("Passport image must not exceed 5 MB."));
        MockMultipartFile passportImage =
                new MockMultipartFile("passportImages", "oversized.jpg", "image/jpeg", new byte[6 * 1024 * 1024]);

        mockMvc.perform(multipart("/guests")
                        .file(passportImage)
                        .param("firstName", "Khoa")
                        .param("lastName", "Nguyen")
                        .param("nationality", "Japan")
                        .with(user("admin").authorities(manageGuestAuthority()))
                        .with(csrf()))
                .andExpect(status().isOk())
                .andExpect(content().string(org.hamcrest.Matchers.containsString(
                        "Passport image must not exceed 5 MB.")))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("value=\"Khoa\"")))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("value=\"Nguyen\"")));
    }

    /**
     * Confirms an oversized file among several selected images safely rejects the whole Create
     * Guest submission with a friendly message rather than an error page, since one invalid file
     * must never let the others be silently persisted.
     *
     * @throws Exception if MockMvc cannot perform the request
     */
    @Test
    void shouldRejectCreateGuestWhenOneOfSeveralSelectedImagesIsOversized() throws Exception {
        when(guestService.create(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any()))
                .thenThrow(new GuestDocumentValidationException("Passport image must not exceed 5 MB."));
        MockMultipartFile validImage =
                new MockMultipartFile("passportImages", "small.jpg", "image/jpeg", new byte[3 * 1024 * 1024]);
        MockMultipartFile oversizedImage =
                new MockMultipartFile("passportImages", "oversized.png", "image/png", new byte[7 * 1024 * 1024]);

        mockMvc.perform(multipart("/guests")
                        .file(validImage)
                        .file(oversizedImage)
                        .param("firstName", "Khoa")
                        .param("lastName", "Nguyen")
                        .param("nationality", "Japan")
                        .with(user("admin").authorities(manageGuestAuthority()))
                        .with(csrf()))
                .andExpect(status().isOk())
                .andExpect(content().string(org.hamcrest.Matchers.containsString(
                        "Passport image must not exceed 5 MB.")))
                .andExpect(content().string(org.hamcrest.Matchers.not(org.hamcrest.Matchers.containsString(
                        "Whitelabel Error Page"))));
    }

    /**
     * Confirms a non-JPEG/PNG passport upload returns to the Create Guest form with a friendly
     * message instead of an error page.
     *
     * @throws Exception if MockMvc cannot perform the request
     */
    @Test
    void shouldReturnCreateFormWithFriendlyMessageForUnsupportedFileType() throws Exception {
        when(guestService.create(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any()))
                .thenThrow(new GuestDocumentValidationException("Passport image must be a JPG or PNG file."));
        MockMultipartFile passportDocument =
                new MockMultipartFile("passportImages", "passport.pdf", "application/pdf", "pdf-bytes".getBytes());

        mockMvc.perform(multipart("/guests")
                        .file(passportDocument)
                        .param("firstName", "Khoa")
                        .param("lastName", "Nguyen")
                        .param("nationality", "Japan")
                        .with(user("admin").authorities(manageGuestAuthority()))
                        .with(csrf()))
                .andExpect(status().isOk())
                .andExpect(content().string(org.hamcrest.Matchers.containsString(
                        "Passport image must be a JPG or PNG file.")))
                .andExpect(content().string(org.hamcrest.Matchers.not(org.hamcrest.Matchers.containsString(
                        "Whitelabel Error Page"))));
    }

    /**
     * Confirms an oversized Edit Guest passport replacement returns to the form with a friendly
     * field-level error while the existing passport remains presented (no raw 413).
     *
     * @throws Exception if MockMvc cannot perform the request
     */
    @Test
    void shouldReturnEditFormWithFriendlyPassportErrorAndPreservedPassportWhenReplacementTooLarge() throws Exception {
        when(guestService.findById(GUEST_ID)).thenReturn(guestResponse());
        when(guestService.update(
                        org.mockito.ArgumentMatchers.eq(GUEST_ID),
                        org.mockito.ArgumentMatchers.any(),
                        org.mockito.ArgumentMatchers.any()))
                .thenThrow(new GuestDocumentValidationException("Passport image must not exceed 5 MB."));
        when(guestDocumentService.findPassports(GUEST_ID))
                .thenReturn(List.of(new GuestDocumentResponse(DOCUMENT_ID, "existing-passport.jpg")));
        MockMultipartFile passportImage =
                new MockMultipartFile("passportImages", "oversized.png", "image/png", new byte[6 * 1024 * 1024]);

        mockMvc.perform(multipart("/guests/{id}", GUEST_ID)
                        .file(passportImage)
                        .param("firstName", "First")
                        .param("lastName", "Last")
                        .param("nationality", "Japan")
                        .with(user("admin").authorities(manageGuestAuthority()))
                        .with(csrf()))
                .andExpect(status().isOk())
                .andExpect(content().string(org.hamcrest.Matchers.containsString(
                        "Passport image must not exceed 5 MB.")))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("View Passport")));
    }

    /** Confirms the Guest detail presents only safe passport-view links and never storage metadata. */
    @Test
    void shouldRenderAndSecurelyServePassportImage() throws Exception {
        when(guestService.findById(GUEST_ID)).thenReturn(guestResponse());
        when(guestDocumentService.findPassports(GUEST_ID))
                .thenReturn(List.of(new GuestDocumentResponse(DOCUMENT_ID, "passport.jpg")));
        when(guestDocumentService.loadPassport(GUEST_ID, DOCUMENT_ID)).thenReturn(new GuestPassportImage(
                new ByteArrayResource(new byte[] {1, 2, 3}), "image/jpeg", "passport.jpg"));

        mockMvc.perform(get("/guests/{id}", GUEST_ID).with(user("admin").authorities(manageGuestAuthority())))
                .andExpect(status().isOk())
                .andExpect(content().string(org.hamcrest.Matchers.containsString("View Passport")))
                .andExpect(content().string(org.hamcrest.Matchers.not(org.hamcrest.Matchers.containsString("storageKey"))))
                .andExpect(content().string(org.hamcrest.Matchers.not(org.hamcrest.Matchers.containsString("passport.jpg"))));
        mockMvc.perform(get("/guests/{guestId}/documents/{documentId}/passport", GUEST_ID, DOCUMENT_ID)
                        .with(user("admin").authorities(manageGuestAuthority())))
                .andExpect(status().isOk())
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.content()
                        .contentType(MediaType.IMAGE_JPEG));
        mockMvc.perform(get("/guests/{guestId}/documents/{documentId}/passport", GUEST_ID, DOCUMENT_ID)
                        .with(user("staff").authorities(staffAuthorities())))
                .andExpect(status().isOk())
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.content()
                        .contentType(MediaType.IMAGE_JPEG));
        mockMvc.perform(get("/guests/{guestId}/documents/{documentId}/passport", GUEST_ID, DOCUMENT_ID)
                        .with(user("no-permission").authorities(
                                new SimpleGrantedAuthority("PERM_VIEW_BOOKING"))))
                .andExpect(status().isForbidden());
    }

    /** Confirms a Guest without an uploaded passport shows the intentional empty state, count 0, and no row/button. */
    @Test
    void shouldNotRenderViewPassportActionWhenNoPassportUploaded() throws Exception {
        when(guestService.findById(GUEST_ID)).thenReturn(guestResponse());
        when(guestDocumentService.findPassports(GUEST_ID)).thenReturn(List.of());

        mockMvc.perform(get("/guests/{id}", GUEST_ID).with(user("admin").authorities(manageGuestAuthority())))
                .andExpect(status().isOk())
                .andExpect(content().string(org.hamcrest.Matchers.not(org.hamcrest.Matchers.containsString(
                        "/passport\""))))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("No passport images on file.")))
                .andExpect(content().string(org.hamcrest.Matchers.containsString(">0</span>")))
                .andExpect(content().string(org.hamcrest.Matchers.containsString(">images</span>")))
                .andExpect(content().string(org.hamcrest.Matchers.not(org.hamcrest.Matchers.containsString(
                        "class=\"profile-document-list\""))));
    }

    /** Confirms Guest Detail renders one document tile (icon + label + secondary text + button) per image. */
    @Test
    void shouldRenderPassportDocumentTileForSingleImage() throws Exception {
        when(guestService.findById(GUEST_ID)).thenReturn(guestResponse());
        when(guestDocumentService.findPassports(GUEST_ID))
                .thenReturn(List.of(new GuestDocumentResponse(DOCUMENT_ID, "passport1.jpg")));

        mockMvc.perform(get("/guests/{id}", GUEST_ID).with(user("admin").authorities(manageGuestAuthority())))
                .andExpect(status().isOk())
                .andExpect(content().string(org.hamcrest.Matchers.containsString(
                        "class=\"passport-document-item\"")))
                .andExpect(content().string(org.hamcrest.Matchers.containsString(
                        "class=\"passport-document-visual\"")))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("Passport 1")))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("Stored passport image")))
                .andExpect(content().string(org.hamcrest.Matchers.containsString(
                        "class=\"passport-document-actions\"")))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("class=\"button button-secondary\"")))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("View Passport")))
                .andExpect(content().string(org.hamcrest.Matchers.containsString(">1</span>")))
                .andExpect(content().string(org.hamcrest.Matchers.containsString(">image</span>")));
    }

    /** Confirms Guest Detail lists every passport image as its own tile with its own View action when multiple exist. */
    @Test
    void shouldRenderAllViewActionsWhenGuestHasMultiplePassportImages() throws Exception {
        UUID secondDocumentId = UUID.fromString("44444444-4444-4444-4444-444444444444");
        when(guestService.findById(GUEST_ID)).thenReturn(guestResponse());
        when(guestDocumentService.findPassports(GUEST_ID)).thenReturn(List.of(
                new GuestDocumentResponse(DOCUMENT_ID, "passport1.jpg"),
                new GuestDocumentResponse(secondDocumentId, "passport2.png")));

        mockMvc.perform(get("/guests/{id}", GUEST_ID).with(user("admin").authorities(manageGuestAuthority())))
                .andExpect(status().isOk())
                .andExpect(content().string(org.hamcrest.Matchers.containsString("Passport 1")))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("Passport 2")))
                .andExpect(content().string(org.hamcrest.Matchers.containsString(
                        "/guests/" + GUEST_ID + "/documents/" + DOCUMENT_ID + "/passport")))
                .andExpect(content().string(org.hamcrest.Matchers.containsString(
                        "/guests/" + GUEST_ID + "/documents/" + secondDocumentId + "/passport")))
                .andExpect(content().string(org.hamcrest.Matchers.containsString(">2</span>")))
                .andExpect(content().string(org.hamcrest.Matchers.containsString(">images</span>")))
                .andExpect(content().string(org.hamcrest.Matchers.not(org.hamcrest.Matchers.containsString(
                        "passport1.jpg"))))
                .andExpect(content().string(org.hamcrest.Matchers.not(org.hamcrest.Matchers.containsString(
                        "passport2.png"))));
    }

    /** Confirms removing one passport image is a CSRF-protected, MANAGE_GUEST-only mutation. */
    @Test
    void shouldRemovePassportImageOnlyWithCsrfAndManageGuestPermission() throws Exception {
        mockMvc.perform(post("/guests/{guestId}/documents/{documentId}/remove", GUEST_ID, DOCUMENT_ID)
                        .with(user("admin").authorities(manageGuestAuthority())))
                .andExpect(status().isForbidden());

        mockMvc.perform(post("/guests/{guestId}/documents/{documentId}/remove", GUEST_ID, DOCUMENT_ID)
                        .with(user("staff").authorities(staffAuthorities()))
                        .with(csrf()))
                .andExpect(status().isForbidden());

        mockMvc.perform(post("/guests/{guestId}/documents/{documentId}/remove", GUEST_ID, DOCUMENT_ID)
                        .with(user("admin").authorities(manageGuestAuthority()))
                        .with(csrf()))
                .andExpect(status().is3xxRedirection())
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl(
                        "/guests/" + GUEST_ID + "/edit"));

        verify(guestDocumentService).removePassportImage(GUEST_ID, DOCUMENT_ID);
    }

    /** Confirms a Guest A URL combined with a Guest B document identifier cannot resolve (IDOR). */
    @Test
    void shouldRejectPassportViewWhenDocumentDoesNotBelongToRequestedGuest() throws Exception {
        UUID otherGuestId = UUID.fromString("55555555-5555-5555-5555-555555555555");
        when(guestDocumentService.loadPassport(otherGuestId, DOCUMENT_ID))
                .thenThrow(new org.springframework.web.server.ResponseStatusException(
                        org.springframework.http.HttpStatus.NOT_FOUND, "Passport image not found."));

        mockMvc.perform(get("/guests/{guestId}/documents/{documentId}/passport", otherGuestId, DOCUMENT_ID)
                        .with(user("admin").authorities(manageGuestAuthority())))
                .andExpect(status().isNotFound());
    }

    /** Confirms Update Guest succeeds and redirects when zero new passport images are selected. */
    @Test
    void shouldUpdateGuestSuccessfullyWithZeroNewPassportImages() throws Exception {
        when(guestService.findById(GUEST_ID)).thenReturn(guestResponse());
        when(guestDocumentService.findPassports(GUEST_ID)).thenReturn(List.of());
        when(guestService.update(
                        org.mockito.ArgumentMatchers.eq(GUEST_ID),
                        org.mockito.ArgumentMatchers.any(),
                        org.mockito.ArgumentMatchers.isNull()))
                .thenReturn(guestResponse());

        mockMvc.perform(multipart("/guests/{id}", GUEST_ID)
                        .param("firstName", "First")
                        .param("lastName", "Last")
                        .param("nationality", "Japan")
                        .with(user("admin").authorities(manageGuestAuthority()))
                        .with(csrf()))
                .andExpect(status().is3xxRedirection())
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl(
                        "/guests/" + GUEST_ID));

        verify(guestService).update(
                org.mockito.ArgumentMatchers.eq(GUEST_ID), org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.isNull());
    }

    /** Confirms Update Guest appends exactly one new passport image without discarding existing ones. */
    @Test
    void shouldUpdateGuestAndAppendOneNewPassportImage() throws Exception {
        when(guestService.findById(GUEST_ID)).thenReturn(guestResponse());
        when(guestDocumentService.findPassports(GUEST_ID))
                .thenReturn(List.of(new GuestDocumentResponse(DOCUMENT_ID, "existing.jpg")));
        when(guestService.update(org.mockito.ArgumentMatchers.eq(GUEST_ID), org.mockito.ArgumentMatchers.any(),
                        org.mockito.ArgumentMatchers.anyList()))
                .thenReturn(guestResponse());
        MockMultipartFile newImage = new MockMultipartFile(
                "passportImages", "new.jpg", "image/jpeg", "new-image".getBytes());

        mockMvc.perform(multipart("/guests/{id}", GUEST_ID)
                        .file(newImage)
                        .param("firstName", "First")
                        .param("lastName", "Last")
                        .param("nationality", "Japan")
                        .with(user("admin").authorities(manageGuestAuthority()))
                        .with(csrf()))
                .andExpect(status().is3xxRedirection())
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl(
                        "/guests/" + GUEST_ID));

        ArgumentCaptor<List<org.springframework.web.multipart.MultipartFile>> captor =
                ArgumentCaptor.forClass(List.class);
        verify(guestService).update(
                org.mockito.ArgumentMatchers.eq(GUEST_ID), org.mockito.ArgumentMatchers.any(), captor.capture());
        assertEquals(1, captor.getValue().size());
        assertEquals("new.jpg", captor.getValue().get(0).getOriginalFilename());
    }

    /** Confirms Update Guest appends multiple new passport images in one submission. */
    @Test
    void shouldUpdateGuestAndAppendMultipleNewPassportImages() throws Exception {
        when(guestService.findById(GUEST_ID)).thenReturn(guestResponse());
        when(guestDocumentService.findPassports(GUEST_ID)).thenReturn(List.of());
        when(guestService.update(org.mockito.ArgumentMatchers.eq(GUEST_ID), org.mockito.ArgumentMatchers.any(),
                        org.mockito.ArgumentMatchers.anyList()))
                .thenReturn(guestResponse());
        MockMultipartFile firstImage = new MockMultipartFile(
                "passportImages", "one.jpg", "image/jpeg", "one".getBytes());
        MockMultipartFile secondImage = new MockMultipartFile(
                "passportImages", "two.png", "image/png", "two".getBytes());

        mockMvc.perform(multipart("/guests/{id}", GUEST_ID)
                        .file(firstImage)
                        .file(secondImage)
                        .param("firstName", "First")
                        .param("lastName", "Last")
                        .param("nationality", "Japan")
                        .with(user("admin").authorities(manageGuestAuthority()))
                        .with(csrf()))
                .andExpect(status().is3xxRedirection());

        ArgumentCaptor<List<org.springframework.web.multipart.MultipartFile>> captor =
                ArgumentCaptor.forClass(List.class);
        verify(guestService).update(
                org.mockito.ArgumentMatchers.eq(GUEST_ID), org.mockito.ArgumentMatchers.any(), captor.capture());
        assertEquals(2, captor.getValue().size());
    }

    /** Confirms a validation failure on Update Guest returns a visible error instead of appearing to do nothing. */
    @Test
    void shouldReturnVisibleErrorWhenUpdateGuestValidationFails() throws Exception {
        when(guestService.findById(GUEST_ID)).thenReturn(guestResponse());
        when(guestDocumentService.findPassports(GUEST_ID)).thenReturn(List.of());

        mockMvc.perform(multipart("/guests/{id}", GUEST_ID)
                        .param("firstName", "")
                        .param("lastName", "Last")
                        .param("nationality", "Japan")
                        .with(user("admin").authorities(manageGuestAuthority()))
                        .with(csrf()))
                .andExpect(status().isOk())
                .andExpect(content().string(org.hamcrest.Matchers.containsString("First name is required.")))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("Please correct the highlighted fields.")));

        org.mockito.Mockito.verify(guestService, org.mockito.Mockito.never())
                .update(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.anyList());
    }

    /** Confirms the rendered Edit Guest page contains no nested &lt;form&gt; elements (HTML forms cannot nest). */
    @Test
    void shouldNotRenderNestedFormsOnEditGuestPage() throws Exception {
        when(guestService.findById(GUEST_ID)).thenReturn(guestResponse());
        when(guestDocumentService.findPassports(GUEST_ID)).thenReturn(List.of(
                new GuestDocumentResponse(DOCUMENT_ID, "passport1.jpg"),
                new GuestDocumentResponse(UUID.fromString("66666666-6666-6666-6666-666666666666"), "passport2.jpg")));

        String html = mockMvc.perform(get("/guests/{id}/edit", GUEST_ID)
                        .with(user("admin").authorities(manageGuestAuthority())))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString();

        assertFormsAreNeverNested(html);
    }

    /** Confirms the Update Guest submit button is a real submit control inside the Edit Guest form. */
    @Test
    void shouldKeepUpdateGuestSubmitButtonInsideTheEditGuestForm() throws Exception {
        when(guestService.findById(GUEST_ID)).thenReturn(guestResponse());
        when(guestDocumentService.findPassports(GUEST_ID))
                .thenReturn(List.of(new GuestDocumentResponse(DOCUMENT_ID, "passport1.jpg")));

        String html = mockMvc.perform(get("/guests/{id}/edit", GUEST_ID)
                        .with(user("admin").authorities(manageGuestAuthority())))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString();

        int formStart = html.indexOf("<form class=\"form-card\"");
        int updateButton = html.indexOf(">Update Guest</button>");
        int formEnd = html.indexOf("</form>", formStart);
        assertTrue(formStart >= 0, "Edit Guest form must render");
        assertTrue(updateButton > formStart && updateButton < formEnd,
                "Update Guest submit button must be located inside the Edit Guest form boundaries");
    }

    /**
     * Fails when {@code <form} tags are nested in the given HTML, since browsers silently close
     * an already-open form when a nested form start tag is encountered, which can strand controls
     * declared afterward (such as a Submit button) outside of any form.
     *
     * @param html rendered page content
     */
    private void assertFormsAreNeverNested(String html) {
        String withoutComments = html.replaceAll("(?s)<!--.*?-->", "");
        int index = 0;
        int depth = 0;
        while (index < withoutComments.length()) {
            int nextOpen = withoutComments.indexOf("<form", index);
            int nextClose = withoutComments.indexOf("</form>", index);
            if (nextOpen == -1 && nextClose == -1) {
                break;
            }
            if (nextOpen != -1 && (nextClose == -1 || nextOpen < nextClose)) {
                depth++;
                assertTrue(depth <= 1, "Found a <form> nested inside another <form> in the rendered page: "
                        + withoutComments.substring(
                                Math.max(0, nextOpen - 20), Math.min(withoutComments.length(), nextOpen + 80)));
                index = nextOpen + 5;
            } else {
                depth--;
                index = nextClose + 7;
            }
        }
        assertEquals(0, depth, "Every opened <form> must be closed (no dangling/mismatched form tags)");
    }

    /** Confirms the redesigned Guest Detail breadcrumb links to the Guest List and shows the actual guestCode. */
    @Test
    void shouldRenderBreadcrumbWithGuestListLinkAndActualGuestCode() throws Exception {
        when(guestService.findById(GUEST_ID)).thenReturn(guestResponse());

        mockMvc.perform(get("/guests/{id}", GUEST_ID).with(user("admin").authorities(manageGuestAuthority())))
                .andExpect(status().isOk())
                .andExpect(content().string(org.hamcrest.Matchers.containsString("class=\"breadcrumb\"")))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("href=\"/guests\">Guests</a>")))
                .andExpect(content().string(org.hamcrest.Matchers.containsString(
                        "class=\"breadcrumb-current\">G000001</span>")))
                .andExpect(content().string(org.hamcrest.Matchers.not(org.hamcrest.Matchers.containsString(
                        "Back to guests"))));
    }

    /** Confirms guestCode is used as the page title and Personal Information card renders every field. */
    @Test
    void shouldRenderGuestCodeAsTitleAndPersonalInformationFields() throws Exception {
        when(guestService.findById(GUEST_ID)).thenReturn(new GuestResponse(
                GUEST_ID,
                "DEMO-G001",
                "Thanh",
                "Le",
                "demo.guest1@example.test",
                "+840900000001",
                "Vietnam",
                java.time.LocalDate.of(1971, 1, 1),
                "1 Demo Street, Ho Chi Minh City"));

        mockMvc.perform(get("/guests/{id}", GUEST_ID).with(user("admin").authorities(manageGuestAuthority())))
                .andExpect(status().isOk())
                .andExpect(content().string(org.hamcrest.Matchers.containsString("<h1>DEMO-G001</h1>")))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("class=\"card reservation-detail-section\"")))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("Personal Information")))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("Thanh")))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("Le")))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("Vietnam")))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("01/01/1971")))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("demo.guest1@example.test")))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("+840900000001")))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("1 Demo Street, Ho Chi Minh City")))
                .andExpect(content().string(org.hamcrest.Matchers.not(org.hamcrest.Matchers.containsString("1971-01-01"))));
    }

    /** Confirms optional fields with no stored value render the neutral placeholder, never null/N/A. */
    @Test
    void shouldRenderNeutralPlaceholderForMissingOptionalFields() throws Exception {
        when(guestService.findById(GUEST_ID)).thenReturn(new GuestResponse(
                GUEST_ID, "G000002", "First", "Last", null, null, null, null, null));

        mockMvc.perform(get("/guests/{id}", GUEST_ID).with(user("admin").authorities(manageGuestAuthority())))
                .andExpect(status().isOk())
                .andExpect(content().string(org.hamcrest.Matchers.not(org.hamcrest.Matchers.containsString("null"))))
                .andExpect(content().string(org.hamcrest.Matchers.not(org.hamcrest.Matchers.containsString("N/A"))))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("—")));
    }

    /** Confirms the Guest list renders five independent filter fields and no generic Search field. */
    @Test
    void shouldRenderFiveIndependentGuestFilterFieldsWithoutGenericSearchField() throws Exception {
        when(guestQueryService.findPage(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.eq(0)))
                .thenReturn(new PageImpl<>(List.of()));

        mockMvc.perform(get("/guests").with(user("admin").authorities(manageGuestAuthority())))
                .andExpect(status().isOk())
                .andExpect(content().string(org.hamcrest.Matchers.containsString("name=\"guestCode\"")))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("name=\"firstName\"")))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("name=\"lastName\"")))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("name=\"email\"")))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("name=\"nationality\"")))
                .andExpect(content().string(org.hamcrest.Matchers.not(
                        org.hamcrest.Matchers.containsString("name=\"query\""))))
                .andExpect(content().string(org.hamcrest.Matchers.not(
                        org.hamcrest.Matchers.containsString(">Search</label>"))));
    }

    /** Confirms the Guest list preserves every active filter while using shared result and pagination markup. */
    @Test
    void shouldRenderFilteredPaginatedGuestListPreservingAllFilters() throws Exception {
        GuestListResponse guest = new GuestListResponse(
                GUEST_ID,
                "G000001",
                "Khoa",
                "Chu",
                "khoa@example.com",
                new com.example.hotel.dto.customer.response.GuestNationalityDisplay("Vietnam", "🇻🇳"));
        when(guestQueryService.findPage(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.eq(0)))
                .thenReturn(new PageImpl<>(List.of(guest), PageRequest.of(0, 10), 11));

        mockMvc.perform(get("/guests")
                        .param("firstName", "Khoa")
                        .param("nationality", "Vietnam")
                        .with(user("admin").authorities(manageGuestAuthority())))
                .andExpect(status().isOk())
                .andExpect(content().string(org.hamcrest.Matchers.containsString(
                        "id=\"firstName\" name=\"firstName\" type=\"search\"")))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("value=\"Khoa\"")))
                .andExpect(content().string(org.hamcrest.Matchers.containsString(
                        "value=\"Vietnam\" selected=\"selected\"")))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("class=\"pagination guest-pagination\"")))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("pagination__segment")))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("pagination__segment--current")))
                .andExpect(content().string(org.hamcrest.Matchers.containsString(
                        "href=\"/guests?firstName=Khoa&amp;nationality=Vietnam&amp;page=1\"")))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("G000001")))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("href=\"/guests/" + GUEST_ID + "\"")))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("🇻🇳")))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("Vietnam")));
    }

    /** Confirms an empty Guest filter result keeps the selected nationality and omits stale table and pagination content. */
    @Test
    void shouldRenderZeroResultGuestFilterWithoutPagination() throws Exception {
        when(guestQueryService.findPage(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.eq(0)))
                .thenReturn(new PageImpl<>(List.of()));

        mockMvc.perform(get("/guests").param("nationality", "Japan")
                        .with(user("admin").authorities(manageGuestAuthority())))
                .andExpect(status().isOk())
                .andExpect(content().string(org.hamcrest.Matchers.containsString("0 results")))
                .andExpect(content().string(org.hamcrest.Matchers.containsString(
                        "No guests match the current filters.")))
                .andExpect(content().string(org.hamcrest.Matchers.containsString(
                        "value=\"Japan\" selected=\"selected\"")))
                .andExpect(content().string(org.hamcrest.Matchers.not(org.hamcrest.Matchers.containsString(
                        "class=\"pagination guest-pagination\""))));
    }

    /** Confirms the Nationality filter is a country-selection dropdown, not a free-text input. */
    @Test
    void shouldRenderNationalityFilterAsCountryDropdown() throws Exception {
        when(guestQueryService.findPage(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.eq(0)))
                .thenReturn(new PageImpl<>(List.of()));

        mockMvc.perform(get("/guests").with(user("admin").authorities(manageGuestAuthority())))
                .andExpect(status().isOk())
                .andExpect(content().string(org.hamcrest.Matchers.containsString(
                        "<select id=\"nationality\" name=\"nationality\">")))
                .andExpect(content().string(org.hamcrest.Matchers.not(org.hamcrest.Matchers.containsString(
                        "id=\"nationality\" name=\"nationality\" type=\"search\""))))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("All nationalities")))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("🇯🇵 Japan")))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("🇻🇳 Vietnam")));
    }

    /** Confirms Reset always points to the unfiltered Guest list regardless of active filters. */
    @Test
    void shouldPointResetToUnfilteredGuestList() throws Exception {
        when(guestQueryService.findPage(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.eq(0)))
                .thenReturn(new PageImpl<>(List.of()));

        mockMvc.perform(get("/guests").param("firstName", "Khoa")
                        .with(user("admin").authorities(manageGuestAuthority())))
                .andExpect(status().isOk())
                .andExpect(content().string(org.hamcrest.Matchers.containsString("href=\"/guests\">Reset</a>")));
    }

    /**
     * Confirms reservation creation retains its existing MANAGE_BOOKING-protected guest lookup.
     *
     * @throws Exception if MockMvc cannot perform the request
     */
    @Test
    void shouldKeepReservationGuestLookupProtectedByManageBooking() throws Exception {
        when(guestQueryService.findAllForReservationCreation())
                .thenReturn(List.of(new GuestLookupResponse(GUEST_ID, "G000001")));

        mockMvc.perform(get("/api/guests/lookup")
                        .with(user("reservation-manager").authorities(manageBookingAuthority())))
                .andExpect(status().isOk());
        mockMvc.perform(get("/api/guests/lookup")
                        .with(user("guest-manager").authorities(manageGuestAuthority())))
                .andExpect(status().isForbidden());
    }

    /**
     * Confirms Guest Management does not expose a REST deletion operation.
     *
     * @throws Exception if MockMvc cannot perform the request
     */
    @Test
    void shouldNotExposeGuestDeleteOperation() throws Exception {
        mockMvc.perform(delete("/api/guests/{id}", GUEST_ID)
                        .with(user("admin").authorities(manageGuestAuthority()))
                        .with(csrf()))
                .andExpect(status().isMethodNotAllowed());
    }

    /** Confirms a client-supplied guest code cannot override the value owned by the backend. */
    @Test
    void shouldIgnoreClientSuppliedGuestCode() throws Exception {
        when(guestService.create(org.mockito.ArgumentMatchers.any())).thenReturn(guestResponse());
        String body =
                "{\"firstName\":\"First\",\"lastName\":\"Last\",\"nationality\":\"Japan\","
                        + "\"guestCode\":\"CLIENT-OVERRIDE\"}";

        mockMvc.perform(post("/api/guests")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body)
                        .with(user("admin").authorities(manageGuestAuthority()))
                        .with(csrf()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.guestCode").value("G000001"));
    }

    /**
     * Supplies ADMIN and MANAGER as the only roles granted Guest Management permission.
     *
     * @return representative administrative usernames
     */
    private static Stream<Arguments> guestManagers() {
        return Stream.of(Arguments.of("admin"), Arguments.of("manager"));
    }

    /** Builds the operational permissions that authorize creating the Guest a booking/check-in workflow needs. */
    private static Stream<Arguments> operationalGuestCreators() {
        return Stream.of(
                Arguments.of("staff-check-in", "PERM_CHECK_IN", "/check-in/walk-in"),
                Arguments.of("booking-agent", "PERM_MANAGE_BOOKING", "/reservations/new"));
    }

    /** Builds authorities that grant no Guest creation capability at all. */
    private static List<SimpleGrantedAuthority> unrelatedAuthorities() {
        return List.of(
                new SimpleGrantedAuthority("PERM_VIEW_BOOKING"),
                new SimpleGrantedAuthority("PERM_CHECK_OUT"),
                new SimpleGrantedAuthority("PERM_MANAGE_PAYMENT"));
    }

    /**
     * Builds the authority used by all Guest Management operations.
     *
     * @return the MANAGE_GUEST authority
     */
    private static List<SimpleGrantedAuthority> manageGuestAuthority() {
        return List.of(new SimpleGrantedAuthority("PERM_MANAGE_GUEST"));
    }

    /**
     * Builds the existing reservation creation authority.
     *
     * @return the MANAGE_BOOKING authority
     */
    private static List<SimpleGrantedAuthority> manageBookingAuthority() {
        return List.of(new SimpleGrantedAuthority("PERM_MANAGE_BOOKING"));
    }

    /**
     * Builds the existing STAFF permission set without Guest Management authority.
     *
     * @return the STAFF authorities
     */
    private static List<SimpleGrantedAuthority> staffAuthorities() {
        return List.of(
                new SimpleGrantedAuthority("PERM_VIEW_BOOKING"),
                new SimpleGrantedAuthority("PERM_CHECK_IN"),
                new SimpleGrantedAuthority("PERM_CHECK_OUT"));
    }

    /**
     * Creates a representative client-safe guest profile response.
     *
     * @return a guest response for controller testing
     */
    private GuestResponse guestResponse() {
        return guestResponse("Japan");
    }

    /**
     * Creates a representative client-safe guest profile response with the given nationality.
     *
     * @param nationality stored nationality text to use for the response
     * @return a guest response for controller testing
     */
    private GuestResponse guestResponse(String nationality) {
        return new GuestResponse(
                GUEST_ID,
                "G000001",
                "First",
                "Last",
                "guest@example.com",
                "0123456789",
                nationality,
                null,
                "Tokyo");
    }

    /** Enables method-security interception for this MVC authorization test slice. */
    @TestConfiguration
    @EnableMethodSecurity
    static class MethodSecurityTestConfiguration {}

    /** Confirms an out-of-range Guest page redirects to the last valid page preserving filter and sort. */
    @Test
    void shouldRedirectOutOfRangeGuestPagePreservingFilterAndSort() throws Exception {
        when(guestQueryService.findPage(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.eq(999)))
                .thenReturn(new PageImpl<>(List.of(), PageRequest.of(999, 10), 25));

        mockMvc.perform(get("/guests").param("page", "999").param("nationality", "Viet Nam")
                        .param("sort", "lastName").param("dir", "asc")
                        .with(user("admin").authorities(manageGuestAuthority())))
                .andExpect(status().is3xxRedirection())
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl("/guests?nationality=Viet+Nam&sort=lastName&dir=asc&page=2"));
    }

    /** Confirms Guest sort headers are links that keep filters and reset the page. */
    @Test
    void shouldRenderGuestSortLinksKeepingFiltersWithoutPage() throws Exception {
        GuestListResponse guest = new GuestListResponse(
                GUEST_ID, "G000001", "Khoa", "Chu", "khoa@example.com",
                new com.example.hotel.dto.customer.response.GuestNationalityDisplay("Japan", "\uD83C\uDDEF\uD83C\uDDF5"));
        when(guestQueryService.findPage(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.eq(1)))
                .thenReturn(new PageImpl<>(List.of(guest), PageRequest.of(1, 10), 25));

        mockMvc.perform(get("/guests").param("page", "1").param("nationality", "Japan")
                        .param("sort", "guestCode").param("dir", "desc")
                        .with(user("admin").authorities(manageGuestAuthority())))
                .andExpect(status().isOk())
                .andExpect(content().string(org.hamcrest.Matchers.containsString(
                        "href=\"/guests?nationality=Japan&amp;sort=firstName&amp;dir=asc\"")))
                .andExpect(content().string(org.hamcrest.Matchers.containsString(
                        "href=\"/guests?nationality=Japan&amp;sort=guestCode&amp;dir=asc\"")))
                .andExpect(content().string(org.hamcrest.Matchers.containsString(
                        "href=\"/guests?nationality=Japan&amp;sort=guestCode&amp;dir=desc&amp;page=2\"")));
    }
}
