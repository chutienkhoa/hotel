package com.example.hotel.common.validation;

import java.nio.charset.StandardCharsets;

/**
 * The single definition of the application account password policy, shared by every place that
 * accepts a password: User Management (create and administrator reset) and the configured
 * bootstrap administrator credentials. It exists so no second, weaker policy can appear next to
 * the one User Management already enforces.
 *
 * <p>The maximum is expressed both in characters and in UTF-8 bytes because the configured
 * {@code PasswordEncoder} silently truncates beyond that byte length, which would make two
 * different passwords interchangeable.</p>
 */
public final class PasswordPolicy {

    /** Minimum accepted password length, in characters. */
    public static final int MIN_LENGTH = 8;

    /** Maximum accepted password length, in characters and in UTF-8 bytes. */
    public static final int MAX_LENGTH = 72;

    /** Prevents instantiation of this stateless policy holder. */
    private PasswordPolicy() {
    }

    /**
     * Determines whether a submitted or configured password satisfies the application policy.
     * The value itself is never logged, stored, or returned by this method.
     *
     * @param password the candidate password, possibly {@code null}
     * @return {@code true} when the password satisfies the length policy
     */
    public static boolean isAcceptable(String password) {
        return password != null
                && password.length() >= MIN_LENGTH
                && password.length() <= MAX_LENGTH
                && password.getBytes(StandardCharsets.UTF_8).length <= MAX_LENGTH;
    }

    /**
     * Returns the human-readable requirement text, used for operator-facing and browser-facing
     * validation messages so both describe exactly the same rule.
     *
     * @return the policy requirement description, containing no credential material
     */
    public static String requirementDescription() {
        return "Password must be between " + MIN_LENGTH + " and " + MAX_LENGTH + " characters.";
    }
}
