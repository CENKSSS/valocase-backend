package com.cenk.valocase.adreward.service;

import org.springframework.stereotype.Component;

/**
 * Single source of the ad reward tuning. EARN_VP_2X and UPGRADE_PLUS_5 have no
 * daily cap or cooldown; availability is gated by active session/context state.
 */
@Component
public class AdRewardPolicy {

    static final double UPGRADE_BUFF_PERCENT = 5.0;
    static final long EARN_VP_MULTIPLIER = 2L;
    static final long MARKET_VP_REWARD = 2500L;

    public double upgradeBuffPercent() {
        return UPGRADE_BUFF_PERCENT;
    }

    public long earnVpMultiplier() {
        return EARN_VP_MULTIPLIER;
    }

    public long marketVpReward() {
        return MARKET_VP_REWARD;
    }
}
