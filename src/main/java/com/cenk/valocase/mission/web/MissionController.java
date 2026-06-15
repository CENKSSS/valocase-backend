package com.cenk.valocase.mission.web;

import java.util.List;
import java.util.UUID;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.cenk.valocase.account.domain.Account;
import com.cenk.valocase.account.service.AccountService;
import com.cenk.valocase.mission.dto.MissionClaimResponse;
import com.cenk.valocase.mission.dto.MissionResponse;
import com.cenk.valocase.mission.service.MissionService;

import lombok.RequiredArgsConstructor;

@RestController
@RequestMapping("/api/v1")
@RequiredArgsConstructor
public class MissionController {

    private final AccountService accountService;
    private final MissionService missionService;

    /** Lists all active missions with the guest's progress and status. */
    @GetMapping("/missions")
    public List<MissionResponse> getMissions(
            @RequestHeader(value = "X-Guest-Token", required = false) String guestToken) {
        Account account = accountService.requireAccountByToken(guestToken);
        return missionService.getMissions(account.getId());
    }

    /** Claims the reward for a completed mission. */
    @PostMapping("/missions/{missionId}/claim")
    public MissionClaimResponse claimMission(
            @RequestHeader(value = "X-Guest-Token", required = false) String guestToken,
            @PathVariable UUID missionId) {
        Account account = accountService.requireAccountByToken(guestToken);
        return missionService.claim(account.getId(), missionId);
    }
}
