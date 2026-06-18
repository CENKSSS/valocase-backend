package com.cenk.valocase.battle.domain;

/**
 * What occupies a lobby slot.
 *
 * <ul>
 *   <li>{@code EMPTY} – not yet filled; eligible for join or (after the delay) Add Bot.</li>
 *   <li>{@code REAL} – a real player who paid the entry cost.</li>
 *   <li>{@code BOT} – a bot manually added by the creator; pays no VP.</li>
 * </ul>
 */
public enum SlotType {
    EMPTY,
    REAL,
    BOT
}
