package com.cenk.valocase.analytics.domain;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

class ClientPlatformTest {

    @Test
    void mapsKnownPlatformsCaseInsensitively() {
        assertEquals(ClientPlatform.ANDROID, ClientPlatform.fromRaw("android"));
        assertEquals(ClientPlatform.IOS, ClientPlatform.fromRaw("iOS"));
        assertEquals(ClientPlatform.EDITOR, ClientPlatform.fromRaw(" EDITOR "));
    }

    @Test
    void unknownOrBlankBecomesUnknown() {
        assertEquals(ClientPlatform.UNKNOWN, ClientPlatform.fromRaw("WindowsPhone"));
        assertEquals(ClientPlatform.UNKNOWN, ClientPlatform.fromRaw(""));
        assertEquals(ClientPlatform.UNKNOWN, ClientPlatform.fromRaw(null));
    }
}
