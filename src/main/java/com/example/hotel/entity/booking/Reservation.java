package com.example.hotel.entity.booking;

import com.example.hotel.entity.common.AuditedEntity;
import com.example.hotel.entity.customer.Guest;
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
import java.util.List;
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

    @Column(name = "external_booking_id")
    private String externalBookingId;

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

    /** Tạo thực thể rỗng cho JPA. */
    protected Reservation() {}

    /**
     * Tạo một reservation nháp từ dữ liệu đặt phòng trực tiếp.
     *
     * @param id định danh reservation
     * @param reservationNumber mã số reservation do backend tạo
     * @param guest khách thực hiện đặt phòng
     * @param checkInDate ngày nhận phòng
     * @param checkOutDate ngày trả phòng
     * @param currency mã tiền tệ
     * @param notes ghi chú đặt phòng
     */
    public Reservation(
            UUID id,
            String reservationNumber,
            Guest guest,
            LocalDate checkInDate,
            LocalDate checkOutDate,
            String currency,
            String notes) {
        this.id = id;
        this.reservationNumber = reservationNumber;
        this.guest = guest;
        this.checkInDate = checkInDate;
        this.checkOutDate = checkOutDate;
        this.currency = currency;
        this.notes = notes;
        source = BookingSource.DIRECT;
        status = ReservationStatus.DRAFT;
        reservedAt = Instant.now();
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
