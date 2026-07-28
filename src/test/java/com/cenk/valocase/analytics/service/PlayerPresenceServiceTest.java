package com.cenk.valocase.analytics.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Duration;
import java.time.Instant;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import com.cenk.valocase.analytics.repository.PlayerSessionRepository;

@ExtendWith(MockitoExtension.class)
class PlayerPresenceServiceTest {

    @Mock private PlayerSessionRepository sessionRepository;

    @InjectMocks private PlayerPresenceService service;

    @Test
    void countsPlayersWhoseHeartbeatIsInsideTheOnlineWindow() {
        when(sessionRepository.countOnlinePlayers(any())).thenReturn(7L);

        assertEquals(7L, service.onlinePlayerCount());

        ArgumentCaptor<Instant> cutoff = ArgumentCaptor.forClass(Instant.class);
        verify(sessionRepository).countOnlinePlayers(cutoff.capture());
        // The cutoff must be one online window in the past, not "now".
        Duration age = Duration.between(cutoff.getValue(), Instant.now());
        assertTrue(age.compareTo(PlayerPresenceService.ONLINE_WINDOW) >= 0,
                "cutoff must be at least one window old, was " + age);
        assertTrue(age.compareTo(PlayerPresenceService.ONLINE_WINDOW.plusSeconds(10)) < 0,
                "cutoff must not be far older than one window, was " + age);
    }

    @Test
    void reportsZeroWhenNobodyIsConnected() {
        when(sessionRepository.countOnlinePlayers(any())).thenReturn(0L);

        assertEquals(0L, service.onlinePlayerCount());
    }
}
