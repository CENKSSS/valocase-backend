package com.cenk.valocase.progression.domain;

import java.util.Optional;

/**
 * A case (weapon) category and the player level at which it unlocks.
 *
 * <p>Category is normally derived from the case id prefix
 * ({@code classic_*}, {@code ghost_*}, ...). A case whose id does not match
 * any known prefix is treated as having no gated category (always openable).
 */
public enum CaseCategory {

    CLASSIC(1),
    GHOST(3),
    BULLDOG(7),
    VANDAL(9),
    MELEE(15);

    private final int unlockLevel;

    CaseCategory(int unlockLevel) {
        this.unlockLevel = unlockLevel;
    }

    /** Player level at which this category becomes openable. */
    public int getUnlockLevel() {
        return unlockLevel;
    }

    /**
     * Infers the category from a case id by its prefix before the first
     * underscore (e.g. {@code "vandal_basic"} -> {@code VANDAL}). Returns empty
     * when the id is null or the prefix matches no known category.
     */
    public static Optional<CaseCategory> fromCaseId(String caseId) {
        if (caseId == null || caseId.isBlank()) {
            return Optional.empty();
        }
        int underscore = caseId.indexOf('_');
        String prefix = underscore >= 0 ? caseId.substring(0, underscore) : caseId;
        for (CaseCategory category : values()) {
            if (category.name().equalsIgnoreCase(prefix)) {
                return Optional.of(category);
            }
        }
        return Optional.empty();
    }
}
