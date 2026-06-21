package com.cenk.valocase.adreward.dto;

import java.util.List;

public record AdRewardStatusResponse(
        List<AdRewardTypeStatus> rewards,
        UpgradeBuffStateResponse upgradeBuff
) {
}
