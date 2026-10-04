package com.example.hotel.common;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.springframework.data.domain.Sort;

/**
 * Maps a screen's public sort keys to known-safe entity properties. A browser-supplied sort key is
 * never passed to Spring Data directly: it is only looked up here, and anything unknown falls back
 * to the screen's default ordering.
 */
public final class SortWhitelist {

    private final Map<String, String> properties;
    private final Sort defaultSort;
    private final List<Sort.Order> tieBreakers;

    /**
     * Creates a whitelist.
     *
     * @param properties public sort key to entity property path; a key that orders by several columns lists their
     *     paths separated by commas, all sorted in the requested direction
     * @param defaultSort ordering used when no valid sort is requested
     * @param tieBreakers deterministic secondary ordering appended to a user-selected sort
     */
    public SortWhitelist(Map<String, String> properties, Sort defaultSort, List<Sort.Order> tieBreakers) {
        this.properties = Map.copyOf(properties);
        this.defaultSort = defaultSort;
        this.tieBreakers = List.copyOf(tieBreakers);
    }

    /**
     * Creates a whitelist limited to a subset of this one's keys, keeping the same default ordering.
     *
     * @param keys the public sort keys the restricted screen supports
     * @return the restricted whitelist
     */
    public SortWhitelist restrictedTo(String... keys) {
        Map<String, String> subset = new LinkedHashMap<>();
        for (String key : keys) {
            if (properties.containsKey(key)) {
                subset.put(key, properties.get(key));
            }
        }
        return new SortWhitelist(subset, defaultSort, tieBreakers);
    }

    /**
     * Resolves the database ordering for the requested sort.
     *
     * @param sort requested public sort key, possibly invalid
     * @param dir requested direction, possibly invalid
     * @return the whitelisted ordering, or the default ordering when either value is unknown
     */
    public Sort resolve(String sort, String dir) {
        String key = key(sort, dir);
        if (key == null) {
            return defaultSort;
        }
        List<String> sortProperties = List.of(properties.get(key).split(","));
        Sort.Direction direction = direction(sort, dir).equals("desc") ? Sort.Direction.DESC : Sort.Direction.ASC;
        List<Sort.Order> orders = new ArrayList<>();
        for (String property : sortProperties) {
            orders.add(new Sort.Order(direction, property));
        }
        tieBreakers.stream().filter(order -> !sortProperties.contains(order.getProperty())).forEach(orders::add);
        return Sort.by(orders);
    }

    /**
     * Returns the requested sort key when both key and direction are valid.
     *
     * @param sort requested public sort key
     * @param dir requested direction
     * @return the valid key, or {@code null} when the default ordering applies
     */
    public String key(String sort, String dir) {
        return sort != null && properties.containsKey(sort) && direction(sort, dir) != null ? sort : null;
    }

    /**
     * Returns the direction only when the sort request is fully valid.
     *
     * @param sort requested public sort key
     * @param dir requested direction
     * @return {@code asc} or {@code desc}, or {@code null} when the default ordering applies
     */
    public String activeDirection(String sort, String dir) {
        return key(sort, dir) == null ? null : direction(sort, dir);
    }

    /**
     * Returns the normalized direction when the direction is valid.
     *
     * @param sort requested public sort key
     * @param dir requested direction
     * @return {@code asc} or {@code desc}, or {@code null} when invalid
     */
    public String direction(String sort, String dir) {
        if (dir == null) {
            return null;
        }
        String normalized = dir.toLowerCase(Locale.ROOT);
        return normalized.equals("asc") || normalized.equals("desc") ? normalized : null;
    }
}
