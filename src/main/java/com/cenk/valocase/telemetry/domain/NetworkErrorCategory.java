package com.cenk.valocase.telemetry.domain;

import java.util.Optional;

/**
 * How a client-side network call failed, in the coarsest terms that are still
 * actionable. An allowlist so the column cannot be used to smuggle free text.
 *
 * <p>These mirror the branches the Unity client can already distinguish in its
 * transport layer; nothing here requires the client to expose a URL, a hostname,
 * or an exception message.
 */
public enum NetworkErrorCategory {

    /** Device reported no reachability; the request never opened a socket. */
    OFFLINE("offline"),
    /** The request was sent and the client gave up waiting. */
    TIMEOUT("timeout"),
    /** Name resolution failed. */
    DNS("dns"),
    /** Transport failed with no HTTP response (connection refused, reset, TLS). */
    TRANSPORT("transport"),
    /** An HTTP response arrived with a 4xx or 5xx status. */
    HTTP_ERROR("http_error"),
    /** A 2xx arrived but the body could not be parsed into the expected shape. */
    INVALID_RESPONSE("invalid_response"),
    /** Anything the client could not place in the categories above. */
    UNKNOWN("unknown");

    private final String wireName;

    NetworkErrorCategory(String wireName) {
        this.wireName = wireName;
    }

    public String wireName() {
        return wireName;
    }

    public static Optional<NetworkErrorCategory> fromWire(String raw) {
        if (raw == null || raw.isBlank()) {
            return Optional.empty();
        }
        String trimmed = raw.trim();
        for (NetworkErrorCategory candidate : values()) {
            if (candidate.wireName.equalsIgnoreCase(trimmed)) {
                return Optional.of(candidate);
            }
        }
        return Optional.empty();
    }
}
