package com.cenk.valocase.analytics.domain;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

class SessionEndReasonTest {

    @Test
    void acceptsClientReasons() {
        assertEquals(SessionEndReason.QUIT, SessionEndReason.fromClient("quit"));
        assertEquals(SessionEndReason.LOGOUT, SessionEndReason.fromClient("LOGOUT"));
    }

    @Test
    void serverOnlyReasonsAreNotAcceptedFromClient() {
        assertEquals(SessionEndReason.UNKNOWN, SessionEndReason.fromClient("REPLACED"));
        assertEquals(SessionEndReason.UNKNOWN, SessionEndReason.fromClient("INACTIVITY_TIMEOUT"));
    }

    @Test
    void blankOrGarbageBecomesUnknown() {
        assertEquals(SessionEndReason.UNKNOWN, SessionEndReason.fromClient(null));
        assertEquals(SessionEndReason.UNKNOWN, SessionEndReason.fromClient(""));
        assertEquals(SessionEndReason.UNKNOWN, SessionEndReason.fromClient("nonsense"));
    }
}
