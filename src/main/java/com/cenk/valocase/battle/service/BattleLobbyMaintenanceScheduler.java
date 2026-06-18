package com.cenk.valocase.battle.service;

import java.util.UUID;

import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * Periodic maintenance for public battle lobbies:
 *
 * <ul>
 *   <li>resolves full (STARTING) lobbies whose 1-second start delay has elapsed
 *       but which no client polled, so a battle never gets stuck unresolved;</li>
 *   <li>cancels stale WAITING lobbies (e.g. a host who created a lobby and left)
 *       and refunds every real occupant, preventing orphaned creator-only
 *       lobbies and stuck VP.</li>
 * </ul>
 *
 * Each lobby is processed in its own transaction (a cross-bean call into the
 * locked, status-guarded {@link BattleLobbyService} methods), so one failure
 * never blocks the rest and nothing is double-charged, double-refunded, or
 * started twice.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class BattleLobbyMaintenanceScheduler {

    private final BattleLobbyService lobbyService;

    @Scheduled(
            fixedDelayString = "${valocase.lobby.maintenance-interval-ms:5000}",
            initialDelayString = "${valocase.lobby.maintenance-initial-delay-ms:5000}")
    public void run() {
        for (UUID id : lobbyService.dueStartingLobbyIds()) {
            try {
                lobbyService.resolveDueLobby(id);
            } catch (RuntimeException ex) {
                log.warn("Failed to resolve due lobby {}: {}", id, ex.getMessage());
            }
        }
        for (UUID id : lobbyService.staleWaitingLobbyIds()) {
            try {
                lobbyService.cancelStaleLobby(id);
            } catch (RuntimeException ex) {
                log.warn("Failed to cancel stale lobby {}: {}", id, ex.getMessage());
            }
        }
    }
}
