package com.cenk.valocase.battle.service;

import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * Drives the server-authoritative Free Lobby Event by delegating to the locked,
 * window-keyed {@link BattleLobbyService#createEventLobby()}.
 *
 * <p>The cron only decides <em>when to check</em>, not when an event happens:
 * the cadence itself is owned by the service and varies with how many players
 * are online, so this fires every minute and the service creates a lobby only on
 * the ticks where the next event is actually due. A minute of granularity keeps
 * the event within a minute of its nominal cadence at negligible cost.
 *
 * <p>When multiple instances run the scheduler at once, the database UNIQUE
 * constraint on {@code event_window_key} lets exactly one insert win and the
 * loser's {@link DataIntegrityViolationException} is swallowed here.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class FreeLobbyEventScheduler {

    private final BattleLobbyService lobbyService;

    @Scheduled(cron = "${valocase.lobby.event-cron:0 * * * * *}", zone = "UTC")
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
