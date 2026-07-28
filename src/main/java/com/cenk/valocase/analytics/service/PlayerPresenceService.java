package com.cenk.valocase.analytics.service;

import java.time.Duration;
import java.time.Instant;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.cenk.valocase.analytics.repository.PlayerSessionRepository;

import lombok.RequiredArgsConstructor;

/**
 * How many distinct real players are online at this instant.
 *
 * <p>"Online" means an open client session in the FOREGROUND state whose last
 * heartbeat is younger than {@link #ONLINE_WINDOW} — the same rule the V76
 * {@code is_client_online} admin views apply. The window is a constant rather
 * than a configuration property precisely so it cannot drift away from those
 * views, which hardcode the same 90 seconds.
 *
 * <p>Only players running a client that reports the precise session lifecycle
 * are counted; a backend-estimated session with no client heartbeat is not
 * treated as presence.
 */
@Service
@RequiredArgsConstructor
public class PlayerPresenceService {

    /** Heartbeat age within which a foreground session still counts as online. */
    public static final Duration ONLINE_WINDOW = Duration.ofSeconds(90);

    private final PlayerSessionRepository sessionRepository;

    /** Distinct real players online right now; 0 when nobody is connected. */
    @Transactional(readOnly = true)
    public long onlinePlayerCount() {
        return sessionRepository.countOnlinePlayers(Instant.now().minus(ONLINE_WINDOW));
    }
}
