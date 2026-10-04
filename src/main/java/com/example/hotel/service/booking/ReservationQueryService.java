package com.example.hotel.service.booking;

import com.example.hotel.common.TableSorts;
import com.example.hotel.dto.booking.response.AccompanyingGuestResponse;
import com.example.hotel.dto.booking.response.ReservationDetailResponse;
import com.example.hotel.dto.booking.response.ReservationEditResponse;
import com.example.hotel.dto.booking.response.ReservationListRoomResponse;
import com.example.hotel.dto.booking.response.ReservationListRowResponse;
import com.example.hotel.dto.booking.response.ReservationSummaryResponse;
import com.example.hotel.dto.booking.request.ReservationListCriteria;
import com.example.hotel.dto.booking.request.ReservationSearchCriteria;
import com.example.hotel.entity.booking.Reservation;
import com.example.hotel.entity.booking.StayRoomAssignment;
import com.example.hotel.entity.booking.ReservationRoom;
import com.example.hotel.entity.booking.ReservationStatus;
import com.example.hotel.entity.customer.Guest;
import com.example.hotel.entity.common.AppUser;
import com.example.hotel.mapper.booking.ReservationMapper;
import com.example.hotel.repository.booking.ReservationGuestRepository;
import com.example.hotel.repository.booking.ReservationRepository;
import com.example.hotel.repository.common.AppUserRepository;
import jakarta.persistence.criteria.CriteriaBuilder;
import jakarta.persistence.criteria.CriteriaQuery;
import jakarta.persistence.criteria.Expression;
import jakarta.persistence.criteria.Join;
import jakarta.persistence.criteria.Predicate;
import jakarta.persistence.criteria.Root;
import jakarta.persistence.criteria.Subquery;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

/**
 * Provides read-only reservation data for API and Thymeleaf presentation layers.
 */
@Service
public class ReservationQueryService {

    private static final int RESERVATION_PAGE_SIZE = 10;

    /** Statuses whose rooms are still the booked {@link ReservationRoom} lines (no Stay occupancy). */
    private static final EnumSet<ReservationStatus> PRE_STAY_STATUSES = EnumSet.of(
            ReservationStatus.DRAFT, ReservationStatus.CONFIRMED, ReservationStatus.CANCELLED,
            ReservationStatus.NO_SHOW);


    private final ReservationRepository reservationRepository;
    private final ReservationMapper reservationMapper;
    private final ReservationGuestRepository reservationGuestRepository;
    private final AppUserRepository appUserRepository;

    /**
     * Creates the query service with the dependencies required to load and map reservations.
     *
     * @param reservationRepository repository used to load reservations
     * @param reservationMapper mapper used to create response DTOs
     * @param reservationGuestRepository repository used to load Accompanying Guests in one query
     * @param appUserRepository repository used to resolve the Reservation creator's username for detail display
     */
    public ReservationQueryService(
            ReservationRepository reservationRepository,
            ReservationMapper reservationMapper,
            ReservationGuestRepository reservationGuestRepository,
            AppUserRepository appUserRepository) {
        this.reservationRepository = reservationRepository;
        this.reservationMapper = reservationMapper;
        this.appUserRepository = appUserRepository;
        this.reservationGuestRepository = reservationGuestRepository;
    }

    /**
     * Retrieves every reservation as a compact list representation.
     *
     * @return the reservation list representations
     */
    @Transactional(readOnly = true)
    public List<ReservationSummaryResponse> findAll() {
        List<Reservation> reservations = reservationRepository.findAll();
        Map<UUID, String> roomNumbersByReservationId = roomNumbersByReservationId(reservations);
        return reservations.stream()
                .map(reservation -> reservationMapper.toSummaryResponse(
                        reservation, roomNumbersByReservationId.getOrDefault(reservation.getId(), "")))
                .toList();
    }

    /**
     * Retrieves the 5 most recently created Reservations for the Dashboard Recent Reservations block,
     * ordered by {@code reservedAt} descending (reservation number descending as a deterministic
     * tie-break). This is a dedicated, isolated query: it does not use or change Reservation List's own
     * {@link #findPage} default sort.
     *
     * @return up to 5 reservation list representations, most recently created first
     */
    @Transactional(readOnly = true)
    public List<ReservationSummaryResponse> findRecent() {
        List<Reservation> recent = reservationRepository.findTop5ByOrderByReservedAtDescReservationNumberDesc();
        Map<UUID, String> roomNumbersByReservationId = roomNumbersByReservationId(recent);
        return recent.stream()
                .map(reservation -> reservationMapper.toSummaryResponse(
                        reservation, roomNumbersByReservationId.getOrDefault(reservation.getId(), "")))
                .toList();
    }

    /**
     * Retrieves one server-side page of Reservations matching the supplied optional criteria.
     *
     * @param criteria normalized optional list filters
     * @param page zero-based requested page number
     * @return a page of compact Reservation list representations
     */
    @Transactional(readOnly = true)
    public Page<ReservationSummaryResponse> findPage(ReservationSearchCriteria criteria, int page) {
        Pageable pageable = PageRequest.of(
                Math.max(page, 0),
                RESERVATION_PAGE_SIZE,
                TableSorts.RESERVATION.resolve(criteria.getSort(), criteria.getDir()));
        Page<Reservation> reservationPage = reservationRepository.findAll(specificationFor(criteria), pageable);
        Map<UUID, String> roomNumbersByReservationId = roomNumbersByReservationId(reservationPage.getContent());
        return reservationPage.map(reservation -> reservationMapper.toSummaryResponse(
                reservation, roomNumbersByReservationId.getOrDefault(reservation.getId(), "")));
    }

    /**
     * Retrieves one server-side page of the Task33 Reservation List. Filtering, sorting and pagination run in the
     * database; the operational rooms of the page's Reservations are then batch-loaded (at most three extra queries,
     * never one per row).
     *
     * @param criteria normalized list filters
     * @param page zero-based requested page number
     * @return a page of Reservation List rows
     */
    @Transactional(readOnly = true)
    public Page<ReservationListRowResponse> findListPage(ReservationListCriteria criteria, int page) {
        Pageable pageable = PageRequest.of(
                Math.max(page, 0),
                RESERVATION_PAGE_SIZE,
                TableSorts.RESERVATION_LIST.resolve(criteria.getSort(), criteria.getDir()));
        Page<Reservation> reservationPage = reservationRepository.findAll(listSpecificationFor(criteria), pageable);
        Map<UUID, List<ReservationListRoomResponse>> roomsByReservationId =
                operationalRoomsByReservationId(reservationPage.getContent());
        return reservationPage.map(reservation -> reservationMapper.toListRow(
                reservation, roomsByReservationId.getOrDefault(reservation.getId(), List.of())));
    }

    /**
     * Batch-loads the rooms each Reservation operationally occupies: the booked rooms before a Stay exists, the open
     * assignments while CHECKED_IN, and the assignments closed at checkout once CHECKED_OUT.
     *
     * @param reservations the page's Reservations
     * @return each Reservation identifier mapped to its rooms ordered by room number
     */
    private Map<UUID, List<ReservationListRoomResponse>> operationalRoomsByReservationId(List<Reservation> reservations) {
        List<UUID> bookedIds = new ArrayList<>();
        List<UUID> checkedInIds = new ArrayList<>();
        List<UUID> checkedOutIds = new ArrayList<>();
        for (Reservation reservation : reservations) {
            switch (reservation.getStatus()) {
                case CHECKED_IN -> checkedInIds.add(reservation.getId());
                case CHECKED_OUT -> checkedOutIds.add(reservation.getId());
                default -> bookedIds.add(reservation.getId());
            }
        }
        Map<UUID, List<ReservationListRoomResponse>> rooms = new LinkedHashMap<>();
        if (!bookedIds.isEmpty()) {
            collectRooms(rooms, reservationRepository.findBookedRoomsForList(bookedIds));
        }
        if (!checkedInIds.isEmpty()) {
            collectRooms(rooms, reservationRepository.findCurrentRoomsForList(checkedInIds));
        }
        if (!checkedOutIds.isEmpty()) {
            collectRooms(rooms, reservationRepository.findFinalRoomsForList(checkedOutIds));
        }
        return rooms;
    }

    private void collectRooms(Map<UUID, List<ReservationListRoomResponse>> rooms, List<Object[]> rows) {
        for (Object[] row : rows) {
            rooms.computeIfAbsent((UUID) row[0], id -> new ArrayList<>())
                    .add(new ReservationListRoomResponse((String) row[1], (String) row[2]));
        }
    }

    /**
     * Builds the Reservation List predicate. The unified search ORs its fields; every other filter is ANDed. A Stay
     * date range {@code [S, E)} matches Reservations whose planned {@code [checkIn, checkOut)} overlaps it.
     */
    private Specification<Reservation> listSpecificationFor(ReservationListCriteria criteria) {
        return (root, query, criteriaBuilder) -> {
            var predicates = new ArrayList<Predicate>();
            if (criteria.getSearch() != null) {
                predicates.add(searchPredicate(
                        root, query, criteriaBuilder, "%" + criteria.getSearch().toLowerCase(Locale.ROOT) + "%"));
            }
            if (criteria.getStatus() != null) {
                predicates.add(criteriaBuilder.equal(root.get("status"), criteria.getStatus()));
            }
            if (criteria.getSource() != null) {
                predicates.add(criteriaBuilder.equal(root.get("source"), criteria.getSource()));
            }
            if (criteria.getStayFrom() != null && criteria.getStayTo() != null) {
                predicates.add(criteriaBuilder.lessThan(root.get("checkInDate"), criteria.getStayTo()));
                predicates.add(criteriaBuilder.greaterThan(root.get("checkOutDate"), criteria.getStayFrom()));
            }
            return criteriaBuilder.and(predicates.toArray(new Predicate[0]));
        };
    }

    /**
     * ORs the unified search fields: reservation number, guest first/last/full name and code, OTA booking reference,
     * and the room number under the same operational room semantics as the Room(s) column. Room matching uses EXISTS
     * subqueries so a multi-room Reservation is never duplicated and the page total stays exact.
     */
    private Predicate searchPredicate(
            Root<Reservation> root, CriteriaQuery<?> query, CriteriaBuilder criteriaBuilder, String pattern) {
        Join<Reservation, Guest> guest = root.join("guest");
        Expression<String> firstName = criteriaBuilder.coalesce(guest.<String>get("firstName"), "");
        Expression<String> lastName = criteriaBuilder.coalesce(guest.<String>get("lastName"), "");
        Expression<String> fullName = criteriaBuilder.trim(
                criteriaBuilder.concat(criteriaBuilder.concat(firstName, " "), lastName));

        Subquery<Integer> bookedRoom = query.subquery(Integer.class);
        Root<ReservationRoom> reservationRoom = bookedRoom.from(ReservationRoom.class);
        bookedRoom.select(criteriaBuilder.literal(1)).where(
                criteriaBuilder.equal(reservationRoom.get("reservation"), root),
                like(criteriaBuilder, reservationRoom.get("room").get("roomNumber"), pattern));

        Subquery<Integer> currentRoom = query.subquery(Integer.class);
        Root<StayRoomAssignment> openAssignment = currentRoom.from(StayRoomAssignment.class);
        currentRoom.select(criteriaBuilder.literal(1)).where(
                criteriaBuilder.equal(openAssignment.get("stay").get("reservation"), root),
                criteriaBuilder.isNull(openAssignment.get("assignedTo")),
                like(criteriaBuilder, openAssignment.get("room").get("roomNumber"), pattern));

        Subquery<Integer> finalRoom = query.subquery(Integer.class);
        Root<StayRoomAssignment> closedAssignment = finalRoom.from(StayRoomAssignment.class);
        finalRoom.select(criteriaBuilder.literal(1)).where(
                criteriaBuilder.equal(closedAssignment.get("stay").get("reservation"), root),
                criteriaBuilder.isNotNull(closedAssignment.get("assignedTo")),
                criteriaBuilder.equal(
                        closedAssignment.get("assignedTo"), closedAssignment.get("stay").get("actualCheckOutAt")),
                like(criteriaBuilder, closedAssignment.get("room").get("roomNumber"), pattern));

        return criteriaBuilder.or(
                like(criteriaBuilder, root.get("reservationNumber"), pattern),
                like(criteriaBuilder, guest.get("firstName"), pattern),
                like(criteriaBuilder, guest.get("lastName"), pattern),
                like(criteriaBuilder, fullName, pattern),
                like(criteriaBuilder, guest.get("guestCode"), pattern),
                like(criteriaBuilder, root.get("otaBookingReference"), pattern),
                criteriaBuilder.and(
                        root.get("status").in(PRE_STAY_STATUSES), criteriaBuilder.exists(bookedRoom)),
                criteriaBuilder.and(
                        criteriaBuilder.equal(root.get("status"), ReservationStatus.CHECKED_IN),
                        criteriaBuilder.exists(currentRoom)),
                criteriaBuilder.and(
                        criteriaBuilder.equal(root.get("status"), ReservationStatus.CHECKED_OUT),
                        criteriaBuilder.exists(finalRoom)));
    }

    private Predicate like(CriteriaBuilder criteriaBuilder, Expression<String> expression, String pattern) {
        return criteriaBuilder.like(criteriaBuilder.lower(expression), pattern);
    }

    /**
     * Batch-loads and joins each Reservation's assigned room numbers in a single extra query,
     * avoiding an N+1 lookup per row and any duplicate Reservation rows a room join would cause.
     *
     * @param reservations Reservations whose assigned room numbers are loaded
     * @return each Reservation identifier mapped to its compact, comma-separated room numbers
     */
    private Map<UUID, String> roomNumbersByReservationId(List<Reservation> reservations) {
        if (reservations.isEmpty()) {
            return Map.of();
        }
        List<UUID> reservationIds = reservations.stream().map(Reservation::getId).toList();
        Map<UUID, List<String>> roomNumbersById = new LinkedHashMap<>();
        for (Object[] row : reservationRepository.findRoomNumbersByReservationIdIn(reservationIds)) {
            UUID reservationId = (UUID) row[0];
            String roomNumber = (String) row[1];
            roomNumbersById.computeIfAbsent(reservationId, id -> new ArrayList<>()).add(roomNumber);
        }
        Map<UUID, String> joined = new LinkedHashMap<>();
        roomNumbersById.forEach((reservationId, roomNumbers) -> joined.put(reservationId, String.join(", ", roomNumbers)));
        return joined;
    }

    /** Builds the database predicate containing only the supplied filters. */
    private Specification<Reservation> specificationFor(ReservationSearchCriteria criteria) {
        return (root, query, criteriaBuilder) -> {
            var predicates = new ArrayList<Predicate>();
            if (criteria.getReservationNumber() != null) {
                predicates.add(criteriaBuilder.like(
                        criteriaBuilder.lower(root.get("reservationNumber")),
                        "%" + criteria.getReservationNumber().toLowerCase(Locale.ROOT) + "%"));
            }
            if (criteria.getGuest() != null) {
                Join<Reservation, Guest> guestJoin = root.join("guest");
                String pattern = "%" + criteria.getGuest().toLowerCase(Locale.ROOT) + "%";
                predicates.add(criteriaBuilder.or(
                        criteriaBuilder.like(criteriaBuilder.lower(guestJoin.get("firstName")), pattern),
                        criteriaBuilder.like(criteriaBuilder.lower(guestJoin.get("lastName")), pattern)));
            }
            if (criteria.getRoom() != null) {
                Join<Reservation, ReservationRoom> roomJoin = root.join("rooms");
                predicates.add(criteriaBuilder.like(
                        criteriaBuilder.lower(roomJoin.get("room").get("roomNumber")),
                        "%" + criteria.getRoom().toLowerCase(Locale.ROOT) + "%"));
                query.distinct(true);
            }
            if (criteria.getCurrentRoom() != null) {
                Subquery<Integer> openAssignment = query.subquery(Integer.class);
                Root<StayRoomAssignment> assignment = openAssignment.from(StayRoomAssignment.class);
                openAssignment.select(criteriaBuilder.literal(1)).where(
                        criteriaBuilder.equal(assignment.get("stay").get("reservation"), root),
                        criteriaBuilder.isNull(assignment.get("assignedTo")),
                        criteriaBuilder.like(
                                criteriaBuilder.lower(assignment.get("room").get("roomNumber")),
                                "%" + criteria.getCurrentRoom().toLowerCase(Locale.ROOT) + "%"));
                predicates.add(criteriaBuilder.exists(openAssignment));
            }
            if (criteria.getSource() != null) {
                predicates.add(criteriaBuilder.equal(root.get("source"), criteria.getSource()));
            }
            if (criteria.getOtaBookingReference() != null) {
                predicates.add(criteriaBuilder.like(
                        criteriaBuilder.lower(root.get("otaBookingReference")),
                        "%" + criteria.getOtaBookingReference().toLowerCase(Locale.ROOT) + "%"));
            }
            if (criteria.getStatus() != null) {
                predicates.add(criteriaBuilder.equal(root.get("status"), criteria.getStatus()));
            }
            if (criteria.getCheckInFrom() != null) {
                predicates.add(criteriaBuilder.greaterThanOrEqualTo(
                        root.get("checkInDate"), criteria.getCheckInFrom()));
            }
            if (criteria.getCheckInTo() != null) {
                predicates.add(criteriaBuilder.lessThanOrEqualTo(
                        root.get("checkInDate"), criteria.getCheckInTo()));
            }
            if (criteria.getCheckOutFrom() != null) {
                predicates.add(criteriaBuilder.greaterThanOrEqualTo(
                        root.get("checkOutDate"), criteria.getCheckOutFrom()));
            }
            if (criteria.getCheckOutTo() != null) {
                predicates.add(criteriaBuilder.lessThanOrEqualTo(
                        root.get("checkOutDate"), criteria.getCheckOutTo()));
            }
            return criteriaBuilder.and(predicates.toArray(new Predicate[0]));
        };
    }

    /**
     * Retrieves one reservation as a detail representation.
     *
     * @param reservationId the reservation identifier
     * @return the reservation detail representation
     * @throws ResponseStatusException if no reservation exists for the identifier
     */
    @Transactional(readOnly = true)
    public ReservationDetailResponse findById(UUID reservationId) {
        Reservation reservation = reservationRepository
                .findById(reservationId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Reservation not found"));
        EffectiveBookingContact contact = EffectiveBookingContact.of(reservation);
        return reservationMapper.toDetailResponse(
                reservation,
                findAccompanyingGuests(reservationId),
                contact.name(),
                contact.phone(),
                contact.email(),
                contact.fromPrimaryGuest(),
                resolveUsername(reservation.getCreatedBy()));
    }

    /**
     * Resolves one audit user identifier to its display username for Reservation Detail presentation.
     *
     * @param userId the audit {@code createdBy} identifier, or {@code null}
     * @return the matching username, or {@code null} when the identifier is absent or unresolved
     */
    private String resolveUsername(UUID userId) {
        return userId == null ? null : appUserRepository.findById(userId).map(AppUser::getUsername).orElse(null);
    }

    /** Retrieves one Reservation in the representation required by the draft edit form. */
    @Transactional(readOnly = true)
    public ReservationEditResponse findForEdit(UUID reservationId) {
        Reservation reservation = reservationRepository
                .findById(reservationId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Reservation not found"));
        return reservationMapper.toEditResponse(
                reservation,
                findAccompanyingGuests(reservationId).stream().map(AccompanyingGuestResponse::guestId).toList());
    }

    /**
     * Loads the Accompanying Guests of one Reservation with a single query (the Guest profiles are fetched with the
     * associations, so there is never one query per Guest).
     *
     * @param reservationId Reservation identifier
     * @return the Accompanying Guests ordered by guest code
     */
    @Transactional(readOnly = true)
    public List<AccompanyingGuestResponse> findAccompanyingGuests(UUID reservationId) {
        return reservationGuestRepository.findByReservationIdWithGuest(reservationId).stream()
                .map(reservationMapper::toAccompanyingGuestResponse)
                .toList();
    }
}
