package com.cenk.valocase.upgrade.dto;

import java.util.List;

/**
 * Request to upgrade owned input items into one or more target skins. Input
 * items are identified by inventory item id (not skinId); ids are validated and
 * parsed server-side.
 *
 * Targets may be sent as {@code targetSkinIds} (one or more) or, for backward
 * compatibility, as a single {@code targetSkinId}.
 */
public record UpgradeRequest(
        List<String> inputItemIds,
        List<String> targetSkinIds,
        String targetSkinId
) {
}
