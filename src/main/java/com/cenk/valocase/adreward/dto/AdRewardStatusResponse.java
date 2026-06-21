package com.cenk.valocase.adreward.dto;

import java.util.List;

public record AdRewardStatusResponse(
        List<AdRewardPlacementStatus> placements
) {
}
