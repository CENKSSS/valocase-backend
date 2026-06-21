package com.cenk.valocase.adreward.service;

import java.util.EnumMap;
import java.util.Map;

import org.springframework.stereotype.Component;

import com.cenk.valocase.adreward.domain.AdRewardType;

/**
 * Single source of the per-placement ad reward limits. Centralised here so the
 * daily cap, cooldown, and buff size are not scattered as magic values across the
 * service.
 */
@Component
public class AdRewardPolicy {

    static final int EARN_VP_2X_DAILY_LIMIT = 5;
    static final long EARN_VP_2X_COOLDOWN_SECONDS = 60L;

    static final int UPGRADE_PLUS_5_DAILY_LIMIT = 5;
    static final long UPGRADE_PLUS_5_COOLDOWN_SECONDS = 60L;
    static final double UPGRADE_BUFF_PERCENT = 5.0;

    private final Map<AdRewardType, Limit> limits = new EnumMap<>(AdRewardType.class);

    public AdRewardPolicy() {
        limits.put(AdRewardType.EARN_VP_2X, new Limit(EARN_VP_2X_DAILY_LIMIT, EARN_VP_2X_COOLDOWN_SECONDS));
        limits.put(AdRewardType.UPGRADE_PLUS_5, new Limit(UPGRADE_PLUS_5_DAILY_LIMIT, UPGRADE_PLUS_5_COOLDOWN_SECONDS));
    }

    public int dailyLimit(AdRewardType type) {
        return limits.get(type).dailyLimit();
    }

    public long cooldownSeconds(AdRewardType type) {
        return limits.get(type).cooldownSeconds();
    }

    public double upgradeBuffPercent() {
        return UPGRADE_BUFF_PERCENT;
    }

    private record Limit(int dailyLimit, long cooldownSeconds) {
    }
}
