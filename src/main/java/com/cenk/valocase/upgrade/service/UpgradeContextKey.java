package com.cenk.valocase.upgrade.service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Collection;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * Stable, server-derived identity of an upgrade selection. Built only from owned
 * input item ids and target skin ids (never client display text), order-independent,
 * so the same selection always maps to the same key for claim, preview, and execute.
 */
public final class UpgradeContextKey {

    private UpgradeContextKey() {
    }

    public static String compute(Collection<UUID> inputItemIds, Collection<String> targetSkinIds) {
        String inputs = inputItemIds.stream().map(UUID::toString).sorted().collect(Collectors.joining(","));
        String targets = targetSkinIds.stream().sorted().collect(Collectors.joining(","));
        return sha256Hex(inputs + "|" + targets);
    }

    private static String sha256Hex(String value) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder(digest.length * 2);
            for (byte b : digest) {
                sb.append(Character.forDigit((b >> 4) & 0xF, 16)).append(Character.forDigit(b & 0xF, 16));
            }
            return sb.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }
}
