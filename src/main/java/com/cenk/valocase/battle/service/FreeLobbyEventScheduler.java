package com.cenk.valocase.battle.service;

import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * Creates one server-authoritative Free Lobby Event every 5 hours by delegating
 * to the locked, window-keyed {@link BattleLobbyService#createEventLobby()}.
 *
 * <p>The cron fires on each 5-hour boundary; the event lobby's {@code event_window_key}
 * is derived from the wall clock (floored to the 5-hour window), so a late run
 * or a restart within the same window creates nothing. When multiple instances run
 * the scheduler at once, the database UNIQUE constraint lets exactly one insert
 * win and the loser's {@link DataIntegrityViolationException} is swallowed here.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class FreeLobbyEventScheduler {

    private final BattleLobbyService lobbyService;

    @Scheduled(cron = "${valocase.lobby.event-cron:0 0 */5 * * *}", zone = "UTC")
    public void run() {
        try {
            lobbyService.createEventLobby();
        } catch (DataIntegrityViolationException ex) {
            log.debug("Free lobby event already created for this window");
        } catch (RuntimeException ex) {
            log.warn("Failed to create free lobby event: {}", ex.getMessage());
        }
    }
}
