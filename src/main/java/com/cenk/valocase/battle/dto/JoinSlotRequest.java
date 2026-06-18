package com.cenk.valocase.battle.dto;

/**
 * Request to join a specific empty slot of a lobby. {@code slotIndex} is
 * required: it is a boxed {@link Integer} so an omitted value deserializes to
 * {@code null} (and is rejected) instead of silently defaulting to slot 0.
 */
public record JoinSlotRequest(Integer slotIndex) {
}
