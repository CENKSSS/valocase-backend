package com.cenk.valocase.upgrade.service;

import java.util.concurrent.ThreadLocalRandom;

import org.springframework.stereotype.Component;

/**
 * Production {@link UpgradeRng} backed by {@link ThreadLocalRandom}.
 */
@Component
public class ThreadLocalUpgradeRng implements UpgradeRng {

    @Override
    public double nextDouble() {
        return ThreadLocalRandom.current().nextDouble();
    }
}
