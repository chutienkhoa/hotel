package com.example.hotel.entity.room;

import com.example.hotel.entity.common.AuditedEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.util.UUID;

/** Represents a read-only classification that may be assigned to one or more rooms. */
@org.hibernate.annotations.BatchSize(size = 25)
@Entity
@Table(name = "room_type")
public class RoomType extends AuditedEntity {

    @Id
    private UUID id;

    @Column(nullable = false, unique = true)
    private String code;

    @Column(nullable = false)
    private String name;

    private String description;

    private Integer capacity;

    @Column(name = "base_price")
    private BigDecimal basePrice;

    @Column(nullable = false)
    private boolean active;

    /** Creates an empty RoomType instance for JPA. */
    protected RoomType() {}

    /**
     * Returns the internal technical identifier.
     *
     * @return the RoomType identifier
     */
    public UUID getId() {
        return id;
    }

    /**
     * Returns the unique RoomType code.
     *
     * @return the RoomType code
     */
    public String getCode() {
        return code;
    }

    /**
     * Returns the display name of the RoomType.
     *
     * @return the RoomType name
     */
    public String getName() {
        return name;
    }

    /**
     * Returns the RoomType description.
     *
     * @return the description, or {@code null} when none has been supplied
     */
    public String getDescription() {
        return description;
    }

    /**
     * Returns the configured room capacity.
     *
     * @return the capacity, or {@code null} when none has been supplied
     */
    public Integer getCapacity() {
        return capacity;
    }

    /**
     * Returns the configured base price.
     *
     * @return the base price, or {@code null} when none has been supplied
     */
    public BigDecimal getBasePrice() {
        return basePrice;
    }

    /**
     * Indicates whether this RoomType is active.
     *
     * @return {@code true} when active
     */
    public boolean isActive() {
        return active;
    }
}
