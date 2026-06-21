package com.cenk.valocase.leaderboard.repository;

import java.util.UUID;

/** Per-account wallet balance used by the Highest Wallet Value board. */
public interface WalletStatRow {

    UUID getAccountId();

    String getDisplayName();

    String getAvatarId();

    long getValue();
}
