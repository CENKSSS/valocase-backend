package com.cenk.valocase.mission.event;

import java.util.UUID;

/**
 * Published by gameplay services (inside their transaction) when a valid action
 * occurs, so missions can advance progress synchronously and atomically.
 */
public record MissionProgressEvent(
        UUID accountId,
        String eventType,
        int quantity
) {
}
