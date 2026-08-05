package com.cenk.valocase.account.service;

/**
 * Why a display name was refused, as a short code safe to write to a log.
 *
 * <p>These exist so a production log line says which rule fired without ever
 * echoing the name the player typed. The name is user-supplied content; the
 * reason code is not.
 *
 * <p>The former {@code INVALID_CHARSET} code is gone: it meant "not ASCII", and
 * letters outside ASCII are now valid. What replaced it is the pair
 * {@link #WHITESPACE} and {@link #INVALID_CHARACTER}, which say which of the two
 * genuinely-disallowed groups was hit.
 */
public enum RegistrationRejectionReason {

    /** No name at all: absent body, null, empty, or nothing left after trimming. */
    BLANK,

    /** Fewer than {@link AccountService#DISPLAY_NAME_MIN_LENGTH} user-perceived characters. */
    TOO_SHORT,

    /**
     * More than {@link AccountService#DISPLAY_NAME_MAX_LENGTH} user-perceived
     * characters, or past the raw-length guard that keeps the value inside its
     * database column.
     */
    TOO_LONG,

    /**
     * Whitespace inside the name. Leading and trailing whitespace is trimmed
     * rather than rejected, so this only ever means whitespace between
     * characters — a space, tab, line break, or a non-breaking/ideographic space.
     */
    WHITESPACE,

    /**
     * A character that is neither a letter, a decimal digit, a combining mark,
     * nor an underscore. Punctuation, symbols, emoji, and invisible formatting
     * characters land here.
     */
    INVALID_CHARACTER
}
