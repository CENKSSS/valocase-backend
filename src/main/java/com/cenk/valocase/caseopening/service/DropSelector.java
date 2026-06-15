package com.cenk.valocase.caseopening.service;

import java.util.List;
import java.util.concurrent.ThreadLocalRandom;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

import com.cenk.valocase.catalog.domain.CaseEntry;
import com.cenk.valocase.common.exception.ApiException;

/**
 * Picks one winning entry from a drop pool using weighted random selection.
 * Higher {@code weight} means higher probability. Entries with a non-positive
 * weight are ignored.
 */
@Component
public class DropSelector {

    /**
     * @param candidates non-empty list of eligible entries (already filtered to
     *                   present/active skins by the caller)
     * @return the selected entry
     */
    public CaseEntry selectWeighted(List<CaseEntry> candidates) {
        long totalWeight = 0L;
        for (CaseEntry entry : candidates) {
            if (entry.getWeight() > 0) {
                totalWeight += entry.getWeight();
            }
        }
        if (totalWeight <= 0L) {
            throw new ApiException(HttpStatus.INTERNAL_SERVER_ERROR,
                    "Invalid drop pool: total weight is not positive");
        }

        long roll = ThreadLocalRandom.current().nextLong(totalWeight);
        long cumulative = 0L;
        for (CaseEntry entry : candidates) {
            if (entry.getWeight() <= 0) {
                continue;
            }
            cumulative += entry.getWeight();
            if (roll < cumulative) {
                return entry;
            }
        }

        // Unreachable given totalWeight > 0, but fail loudly rather than silently.
        throw new ApiException(HttpStatus.INTERNAL_SERVER_ERROR, "Drop selection failed");
    }
}
