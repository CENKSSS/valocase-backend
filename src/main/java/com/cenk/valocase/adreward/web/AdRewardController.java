package com.cenk.valocase.adreward.web;

import java.util.List;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.cenk.valocase.account.domain.Account;
import com.cenk.valocase.account.service.AccountService;
import com.cenk.valocase.adreward.domain.AdRewardType;
import com.cenk.valocase.adreward.dto.AdRewardClaimRequest;
import com.cenk.valocase.adreward.dto.AdRewardClaimResponse;
import com.cenk.valocase.adreward.dto.AdRewardStatusResponse;
import com.cenk.valocase.adreward.service.AdRewardService;
import com.cenk.valocase.common.exception.ApiException;
import com.cenk.valocase.upgrade.service.UpgradeTargets;

import lombok.RequiredArgsConstructor;

@RestController
@RequestMapping("/api/v1/ads/rewards")
@RequiredArgsConstructor
public class AdRewardController {

    private final AccountService accountService;
    private final AdRewardService adRewardService;

    /**
     * Per-placement availability plus active EARN_VP_2X / UPGRADE_PLUS_5 state.
     * The upgrade context is optional: pass the current selection to learn whether
     * that exact selection was already buffed.
     */
    @GetMapping("/status")
    public AdRewardStatusResponse getStatus(
            @RequestHeader(value = "X-Guest-Token", required = false) String guestToken,
            @RequestParam(value = "earnSessionId", required = false) String earnSessionId,
            @RequestParam(value = "inputItemIds", required = false) List<String> inputItemIds,
            @RequestParam(value = "targetSkinIds", required = false) List<String> targetSkinIds,
            @RequestParam(value = "targetSkinId", required = false) String targetSkinId) {
        Account account = accountService.requireAccountByToken(guestToken);
        return adRewardService.getStatus(account.getId(), earnSessionId,
                inputItemIds, mergeTargets(targetSkinIds, targetSkinId));
    }

    /** Activates the server-authoritative reward for a completed rewarded ad. */
    @PostMapping("/claim")
    public AdRewardClaimResponse claim(
            @RequestHeader(value = "X-Guest-Token", required = false) String guestToken,
            @RequestBody(required = false) AdRewardClaimRequest request) {
        Account account = accountService.requireAccountByToken(guestToken);
        if (request == null) {
            throw new ApiException(HttpStatus.BAD_REQUEST,
                    "Request body with rewardType and adToken is required");
        }
        return adRewardService.claim(account.getId(), parseRewardType(request.rewardType()), request);
    }

    /** Expires the active EARN_VP_2X bonus when the player leaves the tap screen. */
    @PostMapping("/earn-vp-2x/clear")
    public AdRewardClaimResponse clearEarnVp2x(
            @RequestHeader(value = "X-Guest-Token", required = false) String guestToken,
            @RequestBody(required = false) AdRewardClaimRequest request) {
        Account account = accountService.requireAccountByToken(guestToken);
        String earnSessionId = request != null ? request.earnSessionId() : null;
        return adRewardService.clearEarnVp2x(account.getId(), earnSessionId);
    }

    // Status mirrors the single-target upgrade context; an unresolvable or multi-target
    // selection yields the generic (no-context) status rather than failing the request.
    private static List<String> mergeTargets(List<String> targetSkinIds, String targetSkinId) {
        try {
            return List.of(UpgradeTargets.normalizeSingleTarget(targetSkinIds, targetSkinId));
        } catch (ApiException e) {
            return List.of();
        }
    }

    private static AdRewardType parseRewardType(String raw) {
        if (raw == null || raw.isBlank()) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "rewardType is required");
        }
        try {
            return AdRewardType.valueOf(raw.trim());
        } catch (IllegalArgumentException e) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "Unknown rewardType: " + raw);
        }
    }
}
