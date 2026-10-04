package com.example.hotel.service.customer;

import com.example.hotel.dto.customer.request.GuestCreateRequest;
import com.example.hotel.dto.customer.request.GuestUpdateRequest;
import com.example.hotel.dto.customer.response.GuestResponse;
import com.example.hotel.entity.customer.Guest;
import com.example.hotel.mapper.customer.GuestMapper;
import com.example.hotel.repository.customer.GuestRepository;
import com.example.hotel.security.CurrentUser;
import com.example.hotel.security.SessionUserPrincipal;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.server.ResponseStatusException;

/** Provides guest management operations while keeping audit and guest-code ownership on the server. */
@Service
public class GuestService {

    private final GuestRepository guestRepository;
    private final GuestMapper guestMapper;
    private final GuestDocumentService guestDocumentService;

    /**
     * Creates the guest service with persistence and response-mapping collaborators.
     *
     * @param guestRepository repository used to access guests and the guest-code sequence
     * @param guestMapper mapper used to prepare client-safe guest responses
     * @param guestDocumentService service used to manage a Guest's passport images
     */
    public GuestService(
            GuestRepository guestRepository, GuestMapper guestMapper, GuestDocumentService guestDocumentService) {
        this.guestRepository = guestRepository;
        this.guestMapper = guestMapper;
        this.guestDocumentService = guestDocumentService;
    }

    /**
     * Retrieves all managed guests.
     *
     * @return the guest profile list
     */
    @Transactional(readOnly = true)
    public List<GuestResponse> findAll() {
        return guestRepository.findAll().stream().map(guestMapper::toResponse).toList();
    }

    /**
     * Retrieves one managed guest by identifier.
     *
     * @param id guest identifier
     * @return the guest profile
     * @throws ResponseStatusException if no guest exists for the identifier
     */
    @Transactional(readOnly = true)
    public GuestResponse findById(UUID id) {
        return guestMapper.toResponse(findGuest(id));
    }

    /**
     * Creates a guest with a sequence-backed guest code and authenticated-user audit values.
     *
     * @param request client-supplied mutable guest profile data
     * @return the persisted guest profile
     */
    @Transactional
    public GuestResponse create(GuestCreateRequest request) {
        return create(request, null);
    }

    /**
     * Creates a guest and, when supplied, validated passport images within the same database
     * transaction. Every selected image is validated before any is stored, so one invalid image
     * rejects the entire creation instead of leaving a Guest with a partial passport batch.
     *
     * @param request client-supplied mutable guest profile data
     * @param passportImages optional browser-uploaded passport images for the booking Guest and
     *     any accompanying travelers
     * @return the persisted guest profile
     */
    @Transactional
    public GuestResponse create(GuestCreateRequest request, List<MultipartFile> passportImages) {
        CurrentUser currentUser = currentUser();
        Guest guest = Guest.create(
                UUID.randomUUID(),
                nextAvailableGuestCode(),
                trimRequired(request.firstName()),
                trimRequired(request.lastName()),
                request.email(),
                request.phone(),
                trimRequired(request.nationality()),
                request.dateOfBirth(),
                normalizeOptional(request.idDocumentNumber()),
                request.address());
        guest.audit(currentUser.id());
        Guest savedGuest = guestRepository.save(guest);
        guestDocumentService.addPassportImages(savedGuest, passportImages, currentUser.id());
        return guestMapper.toResponse(savedGuest);
    }

    /**
     * Updates mutable guest profile data while preserving the guest code and creator audit value.
     *
     * @param id guest identifier
     * @param request client-supplied mutable guest profile data
     * @return the updated guest profile
     * @throws ResponseStatusException if no guest exists for the identifier
     */
    @Transactional
    public GuestResponse update(UUID id, GuestUpdateRequest request) {
        return update(id, request, null);
    }

    /**
     * Updates a guest profile and, when supplied, appends validated passport images to the
     * Guest's existing documents without replacing or removing any of them.
     *
     * @param id guest identifier
     * @param request client-supplied mutable guest profile data
     * @param passportImages optional browser-uploaded passport images to append
     * @return the updated guest profile
     * @throws ResponseStatusException if no guest exists for the identifier
     */
    @Transactional
    public GuestResponse update(UUID id, GuestUpdateRequest request, List<MultipartFile> passportImages) {
        Guest guest = findGuest(id);
        CurrentUser currentUser = currentUser();
        guest.updateProfile(
                trimRequired(request.firstName()),
                trimRequired(request.lastName()),
                request.email(),
                request.phone(),
                trimRequired(request.nationality()),
                request.dateOfBirth(),
                normalizeOptional(request.idDocumentNumber()),
                request.address());
        guest.audit(currentUser.id());
        Guest savedGuest = guestRepository.save(guest);
        guestDocumentService.addPassportImages(savedGuest, passportImages, currentUser.id());
        return guestMapper.toResponse(savedGuest);
    }

    /**
     * Loads an existing guest or produces the standard not-found response.
     *
     * @param id guest identifier
     * @return the persisted guest entity
     * @throws ResponseStatusException if no guest exists for the identifier
     */
    private Guest findGuest(UUID id) {
        return guestRepository
                .findById(id)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Guest not found"));
    }

    /**
     * Allocates a unique guest code from the database sequence without using a maximum-value query.
     *
     * @return an unused guest code with the approved format
     */
    private String nextAvailableGuestCode() {
        String guestCode;
        do {
            guestCode = "G%06d".formatted(guestRepository.nextGuestCodeSequence());
        } while (guestRepository.existsByGuestCode(guestCode));
        return guestCode;
    }

    /**
     * Trims a required form value after Bean Validation has guaranteed it is not blank.
     *
     * @param value required submitted value
     * @return the value without surrounding whitespace
     */
    private String trimRequired(String value) {
        return value.trim();
    }

    /**
     * Normalizes an optional identity value: surrounding whitespace is removed and a blank value is stored as
     * absent. No format is imposed, since national ID and passport numbers differ by country. The value is never
     * logged.
     *
     * @param value optional submitted value
     * @return the trimmed value, or {@code null} when absent or blank
     */
    private String normalizeOptional(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }

    /**
     * Resolves the current JWT or session principal to the audit identity used by persisted entities.
     *
     * @return the authenticated application user
     * @throws ResponseStatusException if the active principal is not an application user
     */
    private CurrentUser currentUser() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        Object principal = authentication == null ? null : authentication.getPrincipal();
        if (principal instanceof CurrentUser currentUser) {
            return currentUser;
        }
        if (principal instanceof SessionUserPrincipal sessionUserPrincipal) {
            return new CurrentUser(sessionUserPrincipal.id(), sessionUserPrincipal.getUsername());
        }
        throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Unauthenticated user");
    }
}
