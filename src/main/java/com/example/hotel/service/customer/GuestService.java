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
import org.springframework.web.server.ResponseStatusException;

/** Provides guest management operations while keeping audit and guest-code ownership on the server. */
@Service
public class GuestService {

    private final GuestRepository guestRepository;
    private final GuestMapper guestMapper;

    /**
     * Creates the guest service with persistence and response-mapping collaborators.
     *
     * @param guestRepository repository used to access guests and the guest-code sequence
     * @param guestMapper mapper used to prepare client-safe guest responses
     */
    public GuestService(GuestRepository guestRepository, GuestMapper guestMapper) {
        this.guestRepository = guestRepository;
        this.guestMapper = guestMapper;
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
        Guest guest = Guest.create(
                UUID.randomUUID(),
                nextAvailableGuestCode(),
                request.firstName(),
                request.lastName(),
                request.email(),
                request.phone(),
                request.nationality(),
                request.dateOfBirth(),
                request.address());
        guest.audit(currentUser().id());
        return guestMapper.toResponse(guestRepository.save(guest));
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
        Guest guest = findGuest(id);
        guest.updateProfile(
                request.firstName(),
                request.lastName(),
                request.email(),
                request.phone(),
                request.nationality(),
                request.dateOfBirth(),
                request.address());
        guest.audit(currentUser().id());
        return guestMapper.toResponse(guestRepository.save(guest));
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
