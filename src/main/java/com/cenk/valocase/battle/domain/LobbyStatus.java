package com.cenk.valocase.battle.domain;

/**
 * Lifecycle of a public battle lobby.
 *
 * <ul>
 *   <li>{@code WAITING} – open and visible; real players may join and the creator
 *       may add bots into empty slots.</li>
 *   <li>{@code STARTING} – every slot is filled; the battle resolves once the
 *       server-authoritative {@code readyAt} instant has passed.</li>
 *   <li>{@code COMPLETED} – the battle has been resolved; the result is immutable.</li>
 *   <li>{@code CANCELLED} – the creator cancelled while still waiting; any charged
 *       entry cost was refunded.</li>
 * </ul>
 */
public enum LobbyStatus {
    WAITING,
    STARTING,
    COMPLETED,
    CANCELLED
}
