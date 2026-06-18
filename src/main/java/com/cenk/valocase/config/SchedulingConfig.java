package com.cenk.valocase.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * Enables Spring's scheduled task support. Used by the battle-lobby maintenance
 * job that cancels stale waiting lobbies (refunding their occupants) and resolves
 * full lobbies that no client polled.
 */
@Configuration
@EnableScheduling
public class SchedulingConfig {
}
