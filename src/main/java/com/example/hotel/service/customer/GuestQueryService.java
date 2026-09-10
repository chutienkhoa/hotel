package com.example.hotel.service.customer;

import com.example.hotel.dto.customer.response.GuestLookupResponse;
import com.example.hotel.repository.customer.GuestRepository;
import java.util.List;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Provides read-only guest lookup data for reservation creation.
 */
@Service
public class GuestQueryService {

    private final GuestRepository guestRepository;

    /**
     * Creates the query service with the repository used to load guests.
     *
     * @param guestRepository repository used to load guests
     */
    public GuestQueryService(GuestRepository guestRepository) {
        this.guestRepository = guestRepository;
    }

    /**
     * Retrieves the guest identifiers and codes required by the reservation form.
     *
     * @return the guest lookup entries
     */
    @Transactional(readOnly = true)
    public List<GuestLookupResponse> findAllForReservationCreation() {
        return guestRepository.findAll().stream()
                .map(guest -> new GuestLookupResponse(guest.getId(), guest.getGuestCode()))
                .toList();
    }
}
