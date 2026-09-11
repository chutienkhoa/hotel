package com.example.hotel.repository.booking;

import com.example.hotel.entity.booking.Reservation;
import com.example.hotel.entity.booking.ReservationStatus;
import java.time.LocalDate;
import java.util.Collection;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/** Cung cấp truy vấn lưu trữ và kiểm tra xung đột reservation. */
public interface ReservationRepository extends JpaRepository<Reservation, UUID> {
    /**
     * Atomically allocates the next daily reservation number in the approved external format.
     *
     * @return the generated reservation number, or empty when the daily sequence is exhausted
     */
    @Query(
            value = """
                    INSERT INTO reservation_number_sequence (
                        reservation_date,
                        last_value
                    ) VALUES (
                        CURRENT_DATE,
                        1
                    )
                    ON CONFLICT (reservation_date)
                    DO UPDATE
                    SET last_value = reservation_number_sequence.last_value + 1
                    WHERE reservation_number_sequence.last_value < 999999
                    RETURNING CONCAT(
                        'R',
                        TO_CHAR(reservation_date, 'YYYYMMDD'),
                        '-',
                        LPAD(last_value::TEXT, 6, '0')
                    )
                    """,
            nativeQuery = true)
    Optional<String> allocateReservationNumber();

    /**
     * Kiểm tra xem một phòng có reservation thuộc các trạng thái được chỉ định bị giao ngày hay
     * không.
     *
     * @param roomId định danh phòng
     * @param in ngày bắt đầu cần kiểm tra
     * @param out ngày kết thúc cần kiểm tra
     * @param statuses các trạng thái reservation được tính là đang chiếm phòng
     * @return {@code true} nếu tồn tại khoảng ngày giao nhau
     */
    @Query(
            "SELECT COUNT(rr) > 0 "
                    + "FROM ReservationRoom rr "
                    + "WHERE rr.room.id = :roomId "
                    + "AND rr.reservation.status IN :statuses "
                    + "AND rr.checkInDate < :out "
                    + "AND rr.checkOutDate > :in")
    boolean hasOverlap(
            @Param("roomId") UUID roomId,
            @Param("in") LocalDate in,
            @Param("out") LocalDate out,
            @Param("statuses") Collection<ReservationStatus> statuses);
}
