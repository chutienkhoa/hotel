package com.example.hotel.entity.booking;

import com.example.hotel.entity.common.AuditedEntity;
import com.example.hotel.entity.customer.Guest;
import com.example.hotel.entity.room.Room;
import jakarta.persistence.CascadeType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.OneToMany;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.sql.Types;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.HashSet;
import java.util.Set;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.hibernate.annotations.JdbcTypeCode;

/** Quản lý thông tin, phòng và trạng thái của một đặt phòng. */
@Entity
@Table(name = "reservation")
public class Reservation extends AuditedEntity {
    @Id private UUID id;

    @Column(name = "reservation_number", nullable = false, unique = true)
    private String reservationNumber;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "guest_id", nullable = false)
    private Guest guest;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private BookingSource source;

    @Column(name = "adult_count", nullable = false)
    private int adultCount;

    @Column(name = "child_count", nullable = false)
    private int childCount;

    @Column(name = "external_booking_id")
    private String externalBookingId;

    @Column(name = "ota_booking_reference")
    private String otaBookingReference;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private ReservationStatus status;

    @Column(nullable = false)
    private Instant reservedAt;

    @Column(nullable = false)
    private LocalDate checkInDate;

    @Column(nullable = false)
    private LocalDate checkOutDate;

    @Column(nullable = false, length = 3, columnDefinition = "CHAR(3)")
    @JdbcTypeCode(Types.CHAR)
    private String currency;

    @Column(nullable = false, precision = 19, scale = 6)
    private BigDecimal totalAmount;

    private String notes;

    @OneToMany(mappedBy = "reservation", cascade = CascadeType.ALL, orphanRemoval = true)
    private List<ReservationRoom> rooms = new ArrayList<>();

    @OneToMany(mappedBy = "reservation", cascade = CascadeType.ALL, orphanRemoval = true)
    private List<ReservationGuest> accompanyingGuests = new ArrayList<>();

    /** Safe V1 default adult count for fixtures and backfilled historical rows. */
    public static final int DEFAULT_ADULT_COUNT = 1;

    /** Safe V1 default child count for fixtures and backfilled historical rows. */
    public static final int DEFAULT_CHILD_COUNT = 0;

    /** Tạo thực thể rỗng cho JPA. */
    protected Reservation() {}

    /**
     * Tạo một reservation nháp từ dữ liệu đặt phòng trực tiếp, with the fixture-default guest composition
     * (1 adult, 0 children); production creation flows must pass explicit counts.
     *
     * @param id định danh reservation
     * @param reservationNumber mã số reservation do backend tạo
     * @param guest khách thực hiện đặt phòng
     * @param checkInDate ngày nhận phòng
     * @param checkOutDate ngày trả phòng
     * @param source nguồn tạo reservation
     * @param currency mã tiền tệ
     * @param notes ghi chú đặt phòng
     */
    public Reservation(
            UUID id,
            String reservationNumber,
            Guest guest,
            LocalDate checkInDate,
            LocalDate checkOutDate,
            BookingSource source,
            String currency,
            String notes) {
        this(id, reservationNumber, guest, checkInDate, checkOutDate, source, null, currency, notes);
    }

    /**
     * Creates a draft Reservation with its staff-entered external OTA booking reference and an EXPLICIT guest
     * composition. This is the constructor every new-Reservation creation flow must use.
     *
     * @param id định danh reservation
     * @param reservationNumber mã số reservation do backend tạo
     * @param guest khách thực hiện đặt phòng (the primary Guest)
     * @param checkInDate ngày nhận phòng
     * @param checkOutDate ngày trả phòng
     * @param adultCount number of adults, at least 1
     * @param childCount number of children, at least 0
     * @param source nguồn tạo reservation
     * @param otaBookingReference the external booking reference entered by staff for an OTA
     *     source; discarded when {@code source} is {@link BookingSource#DIRECT}
     * @param currency mã tiền tệ
     * @param notes ghi chú đặt phòng
     * @throws IllegalArgumentException if {@code adultCount < 1} or {@code childCount < 0}
     */
    public Reservation(
            UUID id,
            String reservationNumber,
            Guest guest,
            LocalDate checkInDate,
            LocalDate checkOutDate,
            int adultCount,
            int childCount,
            BookingSource source,
            String otaBookingReference,
            String currency,
            String notes) {
        this.id = id;
        this.reservationNumber = reservationNumber;
        this.guest = guest;
        this.checkInDate = checkInDate;
        this.checkOutDate = checkOutDate;
        this.currency = currency;
        this.notes = notes;
        this.source = source;
        this.otaBookingReference = normalizeOtaBookingReference(source, otaBookingReference);
        applyGuestComposition(adultCount, childCount);
        status = ReservationStatus.DRAFT;
        reservedAt = Instant.now();
    }

    /**
     * Creates a draft Reservation for existing domain FIXTURES and historical-style data, with the safe V1 default
     * guest composition of {@value #DEFAULT_ADULT_COUNT} adult and {@value #DEFAULT_CHILD_COUNT} children. New
     * Reservations created by production flows must use the constructor with explicit counts instead.
     *
     * @param id định danh reservation
     * @param reservationNumber mã số reservation do backend tạo
     * @param guest khách thực hiện đặt phòng
     * @param checkInDate ngày nhận phòng
     * @param checkOutDate ngày trả phòng
     * @param source nguồn tạo reservation
     * @param otaBookingReference the external booking reference, discarded for DIRECT
     * @param currency mã tiền tệ
     * @param notes ghi chú đặt phòng
     */
    public Reservation(
            UUID id,
            String reservationNumber,
            Guest guest,
            LocalDate checkInDate,
            LocalDate checkOutDate,
            BookingSource source,
            String otaBookingReference,
            String currency,
            String notes) {
        this(id, reservationNumber, guest, checkInDate, checkOutDate, DEFAULT_ADULT_COUNT, DEFAULT_CHILD_COUNT,
                source, otaBookingReference, currency, notes);
    }

    /**
     * Creates a legacy direct Reservation for existing domain fixtures.
     *
     * @param id reservation identifier
     * @param reservationNumber backend-generated reservation number
     * @param guest reservation guest
     * @param checkInDate check-in date
     * @param checkOutDate check-out date
     * @param currency reservation currency
     * @param notes optional reservation notes
     */
    public Reservation(
            UUID id,
            String reservationNumber,
            Guest guest,
            LocalDate checkInDate,
            LocalDate checkOutDate,
            String currency,
            String notes) {
        this(
                id,
                reservationNumber,
                guest,
                checkInDate,
                checkOutDate,
                BookingSource.DIRECT,
                currency,
                notes);
    }

    /**
     * Thêm một phòng đã được gán cho reservation.
     *
     * @param room dòng thông tin phòng của reservation
     */
    public void addRoom(ReservationRoom room) {
        rooms.add(room);
    }

    /**
     * Updates the editable data and complete room-price snapshots of a draft Reservation.
     *
     * @param guest replacement Guest
     * @param checkInDate replacement planned check-in date
     * @param checkOutDate replacement planned check-out date
     * @param source replacement booking source
     * @param currency replacement currency code
     * @param notes replacement optional notes
     * @param updatedRooms complete submitted room snapshots
     */
    public void updateDraft(
            Guest guest,
            LocalDate checkInDate,
            LocalDate checkOutDate,
            BookingSource source,
            String currency,
            String notes,
            List<ReservationRoom> updatedRooms) {
        updateDraft(guest, checkInDate, checkOutDate, adultCount, childCount, source, null, currency, notes, updatedRooms);
    }

    /**
     * Updates a draft Reservation's editable data and room snapshots while KEEPING its current guest composition.
     *
     * @param guest replacement Guest
     * @param checkInDate replacement planned check-in date
     * @param checkOutDate replacement planned check-out date
     * @param source replacement booking source
     * @param otaBookingReference replacement external OTA booking reference; discarded for DIRECT
     * @param currency replacement currency code
     * @param notes replacement optional notes
     * @param updatedRooms complete submitted room snapshots
     */
    public void updateDraft(
            Guest guest,
            LocalDate checkInDate,
            LocalDate checkOutDate,
            BookingSource source,
            String otaBookingReference,
            String currency,
            String notes,
            List<ReservationRoom> updatedRooms) {
        updateDraft(guest, checkInDate, checkOutDate, adultCount, childCount, source, otaBookingReference, currency,
                notes, updatedRooms);
    }

    /**
     * Updates the editable data, source-dependent OTA booking reference, and complete room-price
     * snapshots of a draft Reservation.
     *
     * @param guest replacement Guest
     * @param checkInDate replacement planned check-in date
     * @param checkOutDate replacement planned check-out date
     * @param adultCount replacement number of adults, at least 1
     * @param childCount replacement number of children, at least 0
     * @param source replacement booking source
     * @param otaBookingReference replacement external OTA booking reference; discarded when
     *     {@code source} is {@link BookingSource#DIRECT}
     * @param currency replacement currency code
     * @param notes replacement optional notes
     * @param updatedRooms complete submitted room snapshots
     * @throws IllegalStateException if the Reservation is not a DRAFT
     * @throws IllegalArgumentException if {@code adultCount < 1} or {@code childCount < 0}
     */
    public void updateDraft(
            Guest guest,
            LocalDate checkInDate,
            LocalDate checkOutDate,
            int adultCount,
            int childCount,
            BookingSource source,
            String otaBookingReference,
            String currency,
            String notes,
            List<ReservationRoom> updatedRooms) {
        updateDraft(guest, checkInDate, checkOutDate, adultCount, childCount, source, otaBookingReference, currency,
                notes, updatedRooms, currentAccompanyingGuests(), null);
    }

    /**
     * Updates a draft Reservation including its Accompanying Guests as one aggregate operation, so a changed Primary
     * Guest can never be left in the accompanying set.
     *
     * @param guest replacement Primary Guest
     * @param checkInDate replacement planned check-in date
     * @param checkOutDate replacement planned check-out date
     * @param adultCount replacement number of adults, at least 1
     * @param childCount replacement number of children, at least 0
     * @param source replacement booking source
     * @param otaBookingReference replacement external OTA booking reference; discarded for DIRECT
     * @param currency replacement currency code
     * @param notes replacement optional notes
     * @param updatedRooms complete submitted room snapshots
     * @param accompanyingGuests the complete replacement set of Accompanying Guests (may be empty)
     * @param auditUserId user recorded on newly added associations (only needed when a Guest is added)
     * @throws IllegalStateException if the Reservation is not a DRAFT
     * @throws IllegalArgumentException if the counts are invalid, the Primary Guest is among the Accompanying Guests,
     *     or a Guest appears twice
     */
    public void updateDraft(
            Guest guest,
            LocalDate checkInDate,
            LocalDate checkOutDate,
            int adultCount,
            int childCount,
            BookingSource source,
            String otaBookingReference,
            String currency,
            String notes,
            List<ReservationRoom> updatedRooms,
            List<Guest> accompanyingGuests,
            UUID auditUserId) {
        if (status != ReservationStatus.DRAFT) {
            throw new IllegalStateException("Only draft reservations can be edited");
        }
        applyGuestComposition(adultCount, childCount);
        this.guest = guest;
        applyAccompanyingGuests(accompanyingGuests, auditUserId);
        this.checkInDate = checkInDate;
        this.checkOutDate = checkOutDate;
        this.source = source;
        this.otaBookingReference = normalizeOtaBookingReference(source, otaBookingReference);
        this.currency = currency;
        this.notes = notes;
        reconcileDraftRooms(updatedRooms);
        calculateTotal();
    }

    /**
     * Replaces the complete set of Accompanying Guests of a DRAFT Reservation. Accompanying Guests are optional
     * reusable Guest profiles; their number is independent of the adult and child counts, and they are not assigned to
     * rooms or Stays.
     *
     * @param guests the complete replacement set (may be empty)
     * @param auditUserId user recorded on newly added associations
     * @throws IllegalStateException if the Reservation is not a DRAFT
     * @throws IllegalArgumentException if the Primary Guest is among them or a Guest appears twice
     */
    public void replaceAccompanyingGuests(List<Guest> guests, UUID auditUserId) {
        if (status != ReservationStatus.DRAFT) {
            throw new IllegalStateException("Only draft reservations can be edited");
        }
        applyAccompanyingGuests(guests, auditUserId);
    }

    /**
     * Returns the Accompanying Guest profiles (not the Primary Guest).
     *
     * @return an unmodifiable list of Accompanying Guests
     */
    public List<Guest> getAccompanyingGuests() {
        return currentAccompanyingGuests();
    }

    private List<Guest> currentAccompanyingGuests() {
        return accompanyingGuests.stream().map(ReservationGuest::getGuest).toList();
    }

    /**
     * Enforces the Accompanying Guest invariants and reconciles the associations by Guest identity, keeping existing
     * rows for retained Guests (so a replaced set never deletes and re-inserts the same pair).
     */
    private void applyAccompanyingGuests(List<Guest> requested, UUID auditUserId) {
        List<Guest> wanted = requested == null ? List.of() : requested;
        Map<UUID, Guest> wantedById = new LinkedHashMap<>();
        for (Guest candidate : wanted) {
            if (guest != null && candidate.getId().equals(guest.getId())) {
                throw new IllegalArgumentException("The primary guest cannot also be an accompanying guest");
            }
            if (wantedById.put(candidate.getId(), candidate) != null) {
                throw new IllegalArgumentException("A guest may be an accompanying guest only once");
            }
        }
        accompanyingGuests.removeIf(link -> !wantedById.containsKey(link.getGuest().getId()));
        Set<UUID> retained = new HashSet<>();
        for (ReservationGuest link : accompanyingGuests) {
            retained.add(link.getGuest().getId());
        }
        for (Guest candidate : wantedById.values()) {
            if (!retained.contains(candidate.getId())) {
                ReservationGuest link = new ReservationGuest(this, candidate);
                if (auditUserId != null) {
                    link.audit(auditUserId);
                }
                accompanyingGuests.add(link);
            }
        }
    }

    /**
     * Validates and stores the guest composition. Adults must be at least 1 and children at least 0; nothing is
     * inferred from Guest profiles, rooms or RoomType capacity, and capacity is not checked here.
     */
    private void applyGuestComposition(int adultCount, int childCount) {
        if (adultCount < 1) {
            throw new IllegalArgumentException("adultCount must be at least 1");
        }
        if (childCount < 0) {
            throw new IllegalArgumentException("childCount must not be negative");
        }
        this.adultCount = adultCount;
        this.childCount = childCount;
    }

    /**
     * Returns the number of adults.
     *
     * @return the adult count, at least 1
     */
    public int getAdultCount() {
        return adultCount;
    }

    /**
     * Returns the number of children.
     *
     * @return the child count, at least 0
     */
    public int getChildCount() {
        return childCount;
    }

    /**
     * Returns the physical party size, derived as adults plus children. It is not persisted and is not related to
     * the number of Guest profiles.
     *
     * @return {@code adultCount + childCount}
     */
    public int getPartySize() {
        return adultCount + childCount;
    }

    /**
     * Discards any external OTA booking reference for a DIRECT reservation, since the Hotel
     * System does not generate this value and it must never persist stale OTA data.
     *
     * @param source the reservation's booking source
     * @param otaBookingReference the staff-entered reference, stored verbatim without transformation
     * @return the value to persist: {@code null} for DIRECT, otherwise the value unchanged
     */
    private static String normalizeOtaBookingReference(BookingSource source, String otaBookingReference) {
        return source == BookingSource.DIRECT ? null : otaBookingReference;
    }

    /** Reconciles draft room snapshots by Room identity to avoid replacing unchanged children. */
    private void reconcileDraftRooms(List<ReservationRoom> updatedRooms) {
        Map<UUID, ReservationRoom> existingByRoomId = new HashMap<>();
        for (ReservationRoom existingRoom : rooms) {
            existingByRoomId.put(roomId(existingRoom), existingRoom);
        }
        List<ReservationRoom> additions = new ArrayList<>();
        for (ReservationRoom updatedRoom : updatedRooms) {
            ReservationRoom existingRoom = existingByRoomId.remove(roomId(updatedRoom));
            if (existingRoom == null) {
                additions.add(updatedRoom);
            } else {
                existingRoom.updateDraftSnapshot(
                        updatedRoom.getCheckInDate(),
                        updatedRoom.getCheckOutDate(),
                        updatedRoom.getNightlyRate());
            }
        }
        rooms.removeIf(existingRoom -> existingByRoomId.containsKey(roomId(existingRoom)));
        rooms.addAll(additions);
    }

    private UUID roomId(ReservationRoom reservationRoom) {
        return reservationRoom.getRoom() == null ? null : reservationRoom.getRoom().getId();
    }

    /**
     * Trả về định danh reservation.
     *
     * @return định danh reservation
     */
    public UUID getId() {
        return id;
    }

    /**
     * Returns the guest associated with this reservation for DTO mapping.
     *
     * @return the reservation guest
     */
    public Guest getGuest() {
        return guest;
    }

    /**
     * Trả về trạng thái hiện tại của reservation.
     *
     * @return trạng thái hiện tại
     */
    public ReservationStatus getStatus() {
        return status;
    }

    /**
     * Returns the source selected when the reservation was created.
     *
     * @return the reservation booking source
     */
    public BookingSource getSource() {
        return source;
    }

    /**
     * Returns the external OTA booking reference entered by staff, stored verbatim.
     *
     * @return the OTA booking reference, or {@code null} for a DIRECT reservation
     */
    public String getOtaBookingReference() {
        return otaBookingReference;
    }

    /**
     * Trả về bản sao chỉ đọc của các phòng đã gán.
     *
     * @return danh sách phòng của reservation
     */
    public List<ReservationRoom> getRooms() {
        return List.copyOf(rooms);
    }

    /**
     * Trả về ngày nhận phòng.
     *
     * @return ngày nhận phòng
     */
    public LocalDate getCheckInDate() {
        return checkInDate;
    }

    /**
     * Trả về ngày trả phòng.
     *
     * @return ngày trả phòng
     */
    public LocalDate getCheckOutDate() {
        return checkOutDate;
    }

    /**
     * Trả về mã số reservation duy nhất.
     *
     * @return mã số reservation
     */
    public String getReservationNumber() {
        return reservationNumber;
    }

    /**
     * Trả về tổng số tiền đã tính từ các phòng.
     *
     * @return tổng số tiền reservation
     */
    public BigDecimal getTotalAmount() {
        return totalAmount;
    }

    /**
     * Trả về mã tiền tệ của reservation.
     *
     * @return mã tiền tệ
     */
    public String getCurrency() {
        return currency;
    }

    /**
     * Returns the optional notes supplied with this reservation.
     *
     * @return the reservation notes, or {@code null} when none were supplied
     */
    public String getNotes() {
        return notes;
    }

    /** Tính lại tổng số tiền từ các dòng phòng đã lưu snapshot. */
    public void calculateTotal() {
        totalAmount =
                rooms.stream()
                        .map(ReservationRoom::getTotalAmount)
                        .reduce(BigDecimal.ZERO, BigDecimal::add);
    }

    /** Chuyển reservation từ nháp sang đã xác nhận. */
    public void confirm() {
        transition(ReservationStatus.DRAFT, ReservationStatus.CONFIRMED);
    }

    /**
     * Replaces the Room of one assigned line before check-in. Only a CONFIRMED Reservation may be reassigned; the
     * identity, guest, source, OTA reference, dates, price snapshots and every other line stay unchanged.
     *
     * @param currentRoomId the Room of the line being replaced
     * @param replacement the replacement Room
     * @return the Room that was replaced (its status is not touched)
     * @throws IllegalStateException if the Reservation is not CONFIRMED, the line does not exist, or the
     *     replacement is already assigned to this Reservation
     */
    public Room reassignRoom(UUID currentRoomId, Room replacement) {
        if (status != ReservationStatus.CONFIRMED) {
            throw new IllegalStateException("Only a CONFIRMED reservation can be reassigned");
        }
        ReservationRoom line = findRoomLine(currentRoomId);
        if (line == null) {
            throw new IllegalStateException("Assigned room not found on reservation");
        }
        if (findRoomLine(replacement.getId()) != null) {
            throw new IllegalStateException("Room is already assigned to this reservation");
        }
        Room previous = line.getRoom();
        line.replaceRoom(replacement);
        return previous;
    }

    /**
     * Finds the assigned line for a Room.
     *
     * @param roomId Room identifier
     * @return the line, or {@code null}
     */
    public ReservationRoom findRoomLine(UUID roomId) {
        return rooms.stream().filter(line -> line.getRoom().getId().equals(roomId)).findFirst().orElse(null);
    }

    /** Hủy reservation đã xác nhận. */
    public void cancel() {
        transition(ReservationStatus.CONFIRMED, ReservationStatus.CANCELLED);
    }

    /** Đánh dấu reservation đã xác nhận là khách không đến. */
    public void noShow() {
        transition(ReservationStatus.CONFIRMED, ReservationStatus.NO_SHOW);
    }

    /** Chuyển reservation đã xác nhận sang trạng thái nhận phòng. */
    public void checkIn() {
        transition(ReservationStatus.CONFIRMED, ReservationStatus.CHECKED_IN);
    }

    /**
     * Chuyển reservation đã check-in sang trạng thái đã check-out.
     *
     * @throws IllegalStateException nếu reservation không ở trạng thái CHECKED_IN
     */
    public void checkOut() {
        transition(ReservationStatus.CHECKED_IN, ReservationStatus.CHECKED_OUT);
    }

    /**
     * Thực hiện một chuyển đổi trạng thái hợp lệ.
     *
     * @param from trạng thái nguồn bắt buộc
     * @param to trạng thái đích
     * @throws IllegalStateException nếu trạng thái hiện tại không khớp trạng thái nguồn
     */
    private void transition(ReservationStatus from, ReservationStatus to) {
        if (status != from) {
            throw new IllegalStateException("Invalid reservation state transition");
        }
        status = to;
    }
}
