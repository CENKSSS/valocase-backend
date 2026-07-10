package com.cenk.valocase.analytics.domain;

import java.util.Locale;

public enum ClientPlatform {
    ANDROID,
    IOS,
    EDITOR,
    UNKNOWN;

    public static ClientPlatform fromRaw(String raw) {
        if (raw == null || raw.isBlank()) {
            return UNKNOWN;
        }
        try {
            return ClientPlatform.valueOf(raw.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException ex) {
            return UNKNOWN;
        }
    }
}
