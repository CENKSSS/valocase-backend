package com.cenk.valocase.battle.web;

import java.util.List;
import java.util.UUID;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.cenk.valocase.account.domain.Account;
import com.cenk.valocase.account.service.AccountService;
import com.cenk.valocase.battle.dto.CreateLobbyRequest;
import com.cenk.valocase.battle.dto.LobbyResponse;
import com.cenk.valocase.battle.service.BattleLobbyService;
import com.cenk.valocase.common.exception.ApiException;

import lombok.RequiredArgsConstructor;

/**
 * Public online battle lobbies. Every action is authenticated by the
 * {@code X-Guest-Token} header, mirroring the rest of the API.
 */
@RestController
@RequestMapping("/api/v1/battles/lobbies")
@RequiredArgsConstructor
public class BattleLobbyController {

    private final AccountService accountService;
    private final BattleLobbyService lobbyService;

    /** Creates a public lobby and charges the creator the entry cost. */
    @PostMapping
    public LobbyResponse create(
            @RequestHeader(value = "X-Guest-Token", required = false) String guestToken,
            @RequestBody(required = false) CreateLobbyRequest request) {
        Account account = accountService.requireAccountByToken(guestToken);
        if (request == null) {
            throw new ApiException(HttpStatus.BAD_REQUEST,
                    "Request body with caseId, rounds and maxSlots is required");
        }
        return lobbyService.createLobby(
                account.getId(), request.caseId(), request.rounds(), request.maxSlots());
    }

    /** Lists every public, still-waiting lobby. */
    @GetMapping
    public List<LobbyResponse> list(
            @RequestHeader(value = "X-Guest-Token", required = false) String guestToken) {
        accountService.requireAccountByToken(guestToken);
        return lobbyService.listOpenLobbies();
    }

    /**
     * Returns lobby / waiting-room status, resolving the battle once it is due.
     * Doubles as the heartbeat: a committed real player polling here is marked
     * connected, which keeps them eligible for the win reward.
     */
    @GetMapping("/{battleId}")
    public LobbyResponse get(
            @RequestHeader(value = "X-Guest-Token", required = false) String guestToken,
            @PathVariable String battleId) {
        Account account = accountService.requireAccountByToken(guestToken);
        return lobbyService.getLobby(account.getId(), parseId(battleId));
    }

    /** Joins the lobby as a real player, charging the entry cost on success. */
    @PostMapping("/{battleId}/join")
    public LobbyResponse join(
            @RequestHeader(value = "X-Guest-Token", required = false) String guestToken,
            @PathVariable String battleId) {
        Account account = accountService.requireAccountByToken(guestToken);
        return lobbyService.joinLobby(account.getId(), parseId(battleId));
    }

    /** Creator fills one empty slot with one bot (allowed after the 3-second delay). */
    @PostMapping("/{battleId}/add-bot")
    public LobbyResponse addBot(
            @RequestHeader(value = "X-Guest-Token", required = false) String guestToken,
            @PathVariable String battleId) {
        Account account = accountService.requireAccountByToken(guestToken);
        return lobbyService.addBot(account.getId(), parseId(battleId));
    }

    // No cancel / leave endpoint by design: creating or joining a lobby commits
    // the player. A host who is never joined is released automatically by the
    // server-side creator-only stale rule (which refunds them once).

    private static UUID parseId(String battleId) {
        try {
            return UUID.fromString(battleId);
        } catch (IllegalArgumentException ex) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "Invalid battleId");
        }
    }
}
