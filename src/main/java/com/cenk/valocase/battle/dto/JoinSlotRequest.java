package com.cenk.valocase.battle.dto;

/** Request to join a specific empty slot of a lobby. */
public record JoinSlotRequest(int slotIndex) {
}
