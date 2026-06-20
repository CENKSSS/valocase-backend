package com.cenk.valocase.battle.web;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.cenk.valocase.account.domain.Account;
import com.cenk.valocase.account.service.AccountService;
import com.cenk.valocase.battle.dto.BattleRequest;
import com.cenk.valocase.battle.dto.BattleResultResponse;
import com.cenk.valocase.battle.service.BotBattleService;
import com.cenk.valocase.common.exception.ApiException;

import lombok.RequiredArgsConstructor;

@RestController
@RequestMapping("/api/v1")
@RequiredArgsConstructor
public class BotBattleController {

    private final AccountService accountService;
    private final BotBattleService botBattleService;

    /**
     * Creates and resolves a bot battle for the guest identified by the
     * X-Guest-Token header: charges the entry, rolls, decides the winner, and
     * grants rewards on a win — all atomically.
     */
    @PostMapping("/battles/bot")
    public BattleResultResponse createBotBattle(
            @RequestHeader(value = "X-Guest-Token", required = false) String guestToken,
            @RequestBody(required = false) BattleRequest request) {
        Account account = accountService.requireAccountByToken(guestToken);
        if (request == null) {
            throw new ApiException(HttpStatus.BAD_REQUEST,
                    "Request body with caseId, rounds and participantCount is required");
        }
        String userDisplayName = AccountService.resolveDisplayName(account.getDisplayName(), account.getId());
        String userAvatarId = AccountService.resolveAvatarId(account.getAvatarId());
        return botBattleService.createAndResolve(
                account.getId(), userDisplayName, userAvatarId,
                request.caseId(), request.rounds(), request.participantCount());
    }
}
