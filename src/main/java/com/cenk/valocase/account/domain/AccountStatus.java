package com.cenk.valocase.account.domain;

/**
 * Lifecycle state of an account. Phase 1 only ever uses ACTIVE.
 */
public enum AccountStatus {
    ACTIVE,
    DISABLED
}
