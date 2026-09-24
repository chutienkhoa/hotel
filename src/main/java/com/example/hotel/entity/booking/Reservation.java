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

    @Column(name = "booking_contact_name")
    private String bookingContactName;

    @Column(name = "booking_contact_phone")
    private String bookingContactPhone;

    @Column(name = "booking_contact_email")
    private String bookingContactEmail;

    @Enumerated(EnumType.STRING)
    @Column(name = "cancellation_reason_code")
    private CancellationReasonCode cancellationReasonCode;

    @Column(name = "cancellation_reason_detail")
    private String cancellationReasonDetail;

    @Column(name = "no_show_reason")
    private String noShowReason;

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
        reconcileAccompanyingGuests(requireValidAccompanyingGuests(requested), auditUserId);
    }

    /**
     * Validates a requested Accompanying Guest set WITHOUT changing anything: the Primary Guest may not be in it and no
     * Guest may appear twice.
     *
     * @return the requested Guests keyed by id, in request order
     */
    private Map<UUID, Guest> requireValidAccompanyingGuests(List<Guest> requested) {
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
        return wantedById;
    }

    private void reconcileAccompanyingGuests(Map<UUID, Guest> wantedById, UUID auditUserId) {
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
     * Updates ONLY the guest composition (adults, children and Accompanying Guests) of a CONFIRMED Reservation. This
     * is the dedicated post-confirmation operation; it is not draft editing. The Primary Guest, dates, source, rooms,
     * rates, currency, notes and every other field are untouched. Everything is validated before anything changes, and
     * associations are reconciled by Guest identity so retained rows are never deleted and re-inserted. Capacity is
     * checked by the caller with the shared adult-capacity rule.
     *
     * @param adultCount new number of adults, at least 1
     * @param childCount new number of children, at least 0
     * @param accompanying the complete new Accompanying Guest set (may be empty)
     * @param auditUserId user recorded on newly added associations
     * @throws IllegalStateException if the Reservation is not CONFIRMED
     * @throws IllegalArgumentException if a count is invalid, the Primary Guest is in the set, or a Guest repeats
     */
    public void updateConfirmedGuestComposition(
            int adultCount, int childCount, List<Guest> accompanying, UUID auditUserId) {
        if (status != ReservationStatus.CONFIRMED) {
            throw new IllegalStateException("Only a confirmed reservation can update its guest composition");
        }
        requireValidGuestComposition(adultCount, childCount);
        Map<UUID, Guest> wanted = requireValidAccompanyingGuests(accompanying);
        this.adultCount = adultCount;
        this.childCount = childCount;
        reconcileAccompanyingGuests(wanted, auditUserId);
    }

    /**
     * Validates and stores the guest composition. Adults must be at least 1 and children at least 0; nothing is
     * inferred from Guest profiles, rooms or RoomType capacity, and capacity is not checked here.
     */
    private void applyGuestComposition(int adultCount, int childCount) {
        requireValidGuestComposition(adultCount, childCount);
        this.adultCount = adultCount;
        this.childCount = childCount;
    }

    private static void requireValidGuestComposition(int adultCount, int childCount) {
        if (adultCount < 1) {
            throw new IllegalArgumentException("adultCount must be at least 1");
        }
        if (childCount < 0) {
            throw new IllegalArgumentException("childCount must not be negative");
        }
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

    /**
     * Returns the Booking Contact's snapshot name, independent from the Primary Guest.
     *
     * @return the Booking Contact name, or {@code null} when none is set
     */
    public String getBookingContactName() {
        return bookingContactName;
    }

    /**
     * Returns the Booking Contact's snapshot phone number, independent from the Primary Guest.
     *
     * @return the Booking Contact phone, or {@code null} when none is set
     */
    public String getBookingContactPhone() {
        return bookingContactPhone;
    }

    /**
     * Returns the Booking Contact's snapshot email address, independent from the Primary Guest.
     *
     * @return the Booking Contact email, or {@code null} when none is set
     */
    public String getBookingContactEmail() {
        return bookingContactEmail;
    }

    /**
     * Returns the structured reason this Reservation was cancelled, set exactly once at cancellation.
     *
     * @return the cancellation reason code, or {@code null} for a Reservation that is not CANCELLED, or a
     *     historical CANCELLED Reservation recorded before this feature existed
     */
    public CancellationReasonCode getCancellationReasonCode() {
        return cancellationReasonCode;
    }

    /**
     * Returns the optional free-text detail supplied with the cancellation reason, set exactly once at
     * cancellation.
     *
     * @return the cancellation reason detail, or {@code null} when none was supplied or recorded
     */
    public String getCancellationReasonDetail() {
        return cancellationReasonDetail;
    }

    /**
     * Returns the required free-text operational explanation recorded when this Reservation was marked
     * NO_SHOW, set exactly once at that transition.
     *
     * @return the no-show reason, or {@code null} for a Reservation that is not NO_SHOW, or a historical
     *     NO_SHOW Reservation recorded before this feature existed
     */
    public String getNoShowReason() {
        return noShowReason;
    }

    /** Tính lại tổng số tiền từ các dòng phòng đã lưu snapshot. */
    public void calculateTotal() {
        totalAmount =
                rooms.stream()
                        .map(ReservationRoom::getTotalAmount)
                        .reduce(BigDecimal.ZERO, BigDecimal::add);
    }

    /**
     * Changes the planned dates of a CONFIRMED Reservation before check-in, synchronizing every booked-room
     * snapshot and recalculating totals from each preserved nightly rate. Room references, nightly rates, source,
     * currency and all other Reservation fields remain untouched.
     *
     * @param newCheckInDate replacement planned check-in date
     * @param newCheckOutDate replacement planned check-out date
     * @throws IllegalStateException if this Reservation is not CONFIRMED
     * @throws IllegalArgumentException if the date interval is not positive
     */
    public void changeConfirmedDates(LocalDate newCheckInDate, LocalDate newCheckOutDate) {
        if (status != ReservationStatus.CONFIRMED) {
            throw new IllegalStateException("Only a confirmed reservation can change dates");
        }
        if (newCheckInDate == null || newCheckOutDate == null || !newCheckOutDate.isAfter(newCheckInDate)) {
            throw new IllegalArgumentException("check_out_date must be after check_in_date");
        }
        for (ReservationRoom room : rooms) {
            room.changeConfirmedDates(newCheckInDate, newCheckOutDate);
        }
        checkInDate = newCheckInDate;
        checkOutDate = newCheckOutDate;
        calculateTotal();
    }

    /**
     * Corrects only the external OTA booking reference of a CONFIRMED, non-DIRECT Reservation. The value follows
     * the existing Reservation behavior: surrounding whitespace is preserved, while null/blank and values longer
     * than the existing 255-character limit are rejected.
     *
     * @param correctedReference corrected OTA booking reference
     * @throws IllegalStateException if this Reservation is not CONFIRMED or is DIRECT
     * @throws IllegalArgumentException if the reference is blank or too long
     */
    public void correctOtaBookingReference(String correctedReference) {
        if (status != ReservationStatus.CONFIRMED) {
            throw new IllegalStateException("Only a confirmed reservation can correct its OTA booking reference");
        }
        if (source == BookingSource.DIRECT) {
            throw new IllegalStateException("DIRECT reservations do not have an OTA booking reference");
        }
        if (correctedReference == null || correctedReference.isBlank()) {
            throw new IllegalArgumentException("OTA Booking Reference is required for this source");
        }
        if (correctedReference.length() > 255) {
            throw new IllegalArgumentException("OTA Booking Reference must not exceed 255 characters");
        }
        otaBookingReference = correctedReference;
    }

    /**
     * Replaces the Booking Contact snapshot (name, phone, email) while this Reservation is still in an editable
     * lifecycle state. Booking Contact is a Reservation-level snapshot independent from the Primary Guest,
     * Accompanying Guests, {@code otaBookingReference} and {@code externalBookingId}; none of those are read or
     * changed here. Every field is individually optional.
     *
     * @param name replacement Booking Contact name, or {@code null}
     * @param phone replacement Booking Contact phone, or {@code null}
     * @param email replacement Booking Contact email, or {@code null}
     * @throws IllegalStateException if this Reservation is CHECKED_OUT, CANCELLED or NO_SHOW
     */
    public void changeBookingContact(String name, String phone, String email) {
        requireEditableForContactAndNotes();
        bookingContactName = name;
        bookingContactPhone = phone;
        bookingContactEmail = email;
    }

    /**
     * Replaces the internal operational Reservation Notes while this Reservation is still in an editable lifecycle
     * state. Notes remain a single free-text field; no history is kept beyond the audit trail of the change itself.
     *
     * @param notes replacement notes, or {@code null}
     * @throws IllegalStateException if this Reservation is CHECKED_OUT, CANCELLED or NO_SHOW
     */
    public void changeNotes(String notes) {
        requireEditableForContactAndNotes();
        this.notes = notes;
    }

    /**
     * Rejects a Booking Contact or Notes change once this Reservation has reached a state where those narrow
     * fields are no longer editable: CHECKED_OUT, CANCELLED or NO_SHOW. DRAFT, CONFIRMED and CHECKED_IN are all
     * editable, matching the approved lifecycle for these two controlled operations.
     */
    private void requireEditableForContactAndNotes() {
        if (status == ReservationStatus.CHECKED_OUT || status == ReservationStatus.CANCELLED
                || status == ReservationStatus.NO_SHOW) {
            throw new IllegalStateException(
                    "Booking Contact and Notes can no longer be changed once a reservation is "
                            + status.name());
        }
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

    /**
     * Hủy reservation nháp (abandoned draft) hoặc đã xác nhận và ghi nhận lý do hủy. The reason is set exactly
     * once: this method only ever succeeds from DRAFT or CONFIRMED, and CANCELLED is terminal, so the reason can
     * never be overwritten. The caller is expected to have already validated the reason (required code; non-blank
     * detail when the code is OTHER).
     *
     * @param reasonCode the required structured cancellation reason
     * @param reasonDetail the optional free-text detail, or {@code null}
     * @throws IllegalStateException if this Reservation is neither DRAFT nor CONFIRMED
     */
    public void cancel(CancellationReasonCode reasonCode, String reasonDetail) {
        if (status != ReservationStatus.DRAFT && status != ReservationStatus.CONFIRMED) {
            throw new IllegalStateException("Invalid reservation state transition");
        }
        status = ReservationStatus.CANCELLED;
        cancellationReasonCode = reasonCode;
        cancellationReasonDetail = reasonDetail;
    }

    /**
     * Đánh dấu reservation đã xác nhận là khách không đến và ghi nhận lý do. The reason is set exactly once:
     * this method only ever succeeds from CONFIRMED, and NO_SHOW is terminal, so the reason can never be
     * overwritten. The caller is expected to have already validated the reason (required, non-blank).
     *
     * @param reason the required free-text operational explanation
     * @throws IllegalStateException if this Reservation is not CONFIRMED
     */
    public void noShow(String reason) {
        transition(ReservationStatus.CONFIRMED, ReservationStatus.NO_SHOW);
        noShowReason = reason;
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
     * Moves the CURRENT planned check-out date forward for a CHECKED_IN reservation (Stay Extension). The original
     * booking snapshot (the {@link ReservationRoom} lines and {@link #getTotalAmount()}) is never touched.
     *
     * @param newCheckOutDate the later planned check-out date
     * @throws IllegalStateException if the reservation is not CHECKED_IN or the date does not move forward
     */
    public void extendCheckOut(LocalDate newCheckOutDate) {
        if (status != ReservationStatus.CHECKED_IN) {
            throw new IllegalStateException("Only a CHECKED_IN reservation can be extended");
        }
        if (!newCheckOutDate.isAfter(checkOutDate)) {
            throw new IllegalStateException("Extension must move the planned check-out forward");
        }
        checkOutDate = newCheckOutDate;
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
