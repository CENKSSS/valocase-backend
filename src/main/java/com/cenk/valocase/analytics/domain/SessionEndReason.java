package com.cenk.valocase.analytics.domain;

import java.util.Locale;

/**
 * Normalized session close reasons. QUIT/LOGOUT/UNKNOWN may be reported by the
 * client; REPLACED and INACTIVITY_TIMEOUT are assigned by the server only.
 */
public enum SessionEndReason {
    QUIT,
    LOGOUT,
    REPLACED,
    INACTIVITY_TIMEOUT,
    UNKNOWN;

    public static SessionEndReason fromClient(String raw) {
        if (raw == null || raw.isBlank()) {
            return UNKNOWN;
        }
        try {
            SessionEndReason parsed = SessionEndReason.valueOf(raw.trim().toUpperCase(Locale.ROOT));
            return switch (parsed) {
                case QUIT, LOGOUT -> parsed;
                default -> UNKNOWN;
            };
        } catch (IllegalArgumentException ex) {
            return UNKNOWN;
        }
    }
}
