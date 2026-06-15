package com.cenk.valocase.upgrade.dto;

import java.util.List;

/**
 * Request to upgrade owned input items into a target skin. Input items are
 * identified by inventory item id (not skinId); ids are validated and parsed
 * server-side.
 */
public record UpgradeRequest(
        List<String> inputItemIds,
        String targetSkinId
) {
}
