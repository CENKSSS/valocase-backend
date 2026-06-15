package com.cenk.valocase.battle.service;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

import org.springframework.context.ApplicationEventPublisher;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.cenk.valocase.battle.domain.Battle;
import com.cenk.valocase.battle.domain.BattleParticipant;
import com.cenk.valocase.battle.domain.BattleRoll;
import com.cenk.valocase.battle.dto.BattleParticipantResponse;
import com.cenk.valocase.battle.dto.BattleResultResponse;
import com.cenk.valocase.battle.dto.RolledSkinResponse;
import com.cenk.valocase.battle.repository.BattleParticipantRepository;
import com.cenk.valocase.battle.repository.BattleRepository;
import com.cenk.valocase.battle.repository.BattleRollRepository;
import com.cenk.valocase.caseopening.service.DropSelector;
import com.cenk.valocase.catalog.domain.CaseDefinition;
import com.cenk.valocase.catalog.domain.CaseEntry;
import com.cenk.valocase.catalog.domain.Skin;
import com.cenk.valocase.catalog.repository.CaseDefinitionRepository;
import com.cenk.valocase.catalog.repository.CaseEntryRepository;
import com.cenk.valocase.catalog.repository.SkinRepository;
import com.cenk.valocase.common.exception.ApiException;
import com.cenk.valocase.inventory.domain.InventoryItem;
import com.cenk.valocase.inventory.service.InventoryService;
import com.cenk.valocase.mission.event.MissionEventTypes;
import com.cenk.valocase.mission.event.MissionProgressEvent;
import com.cenk.valocase.wallet.service.WalletService;

import lombok.RequiredArgsConstructor;

/**
 * Server-authoritative bot battle: one transaction that charges the entry,
 * rolls skins for the user (index 0) and bots, decides the winner, and grants
 * all rolled skins to the user only on a win. Any failure rolls back the charge,
 * the records, and any grants together.
 */
@Service
@RequiredArgsConstructor
public class BotBattleService {

    public static final int ROUNDS_MIN = 1;
    public static final int ROUNDS_MAX = 5;
    public static final int PARTICIPANTS_MIN = 2;
    public static final int PARTICIPANTS_MAX = 4;

    public static final String REASON_BATTLE_ENTRY = "BATTLE_ENTRY";
    public static final String INVENTORY_SOURCE_BATTLE_REWARD = "BATTLE_REWARD";

    private final CaseDefinitionRepository caseDefinitionRepository;
    private final CaseEntryRepository caseEntryRepository;
    private final SkinRepository skinRepository;
    private final WalletService walletService;
    private final InventoryService inventoryService;
    private final DropSelector dropSelector;
    private final BattleResolver battleResolver;
    private final BattleRepository battleRepository;
    private final BattleParticipantRepository battleParticipantRepository;
    private final BattleRollRepository battleRollRepository;
    private final ApplicationEventPublisher eventPublisher;

    @Transactional
    public BattleResultResponse createAndResolve(UUID accountId, String caseId, int rounds, int participantCount) {
        // 1. Validate lobby parameters.
        if (rounds < ROUNDS_MIN || rounds > ROUNDS_MAX) {
            throw new ApiException(HttpStatus.BAD_REQUEST,
                    "rounds must be between " + ROUNDS_MIN + " and " + ROUNDS_MAX);
        }
        if (participantCount < PARTICIPANTS_MIN || participantCount > PARTICIPANTS_MAX) {
            throw new ApiException(HttpStatus.BAD_REQUEST,
                    "participantCount must be between " + PARTICIPANTS_MIN + " and " + PARTICIPANTS_MAX);
        }
        if (caseId == null || caseId.isBlank()) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "caseId is required");
        }

        // 2. Load the case and require it to be active.
        CaseDefinition caseDef = caseDefinitionRepository.findById(caseId)
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "Case not found: " + caseId));
        if (!caseDef.isActive()) {
            throw new ApiException(HttpStatus.NOT_FOUND, "Case is not available: " + caseId);
        }

        // 3. Load the drop pool; reject an empty configuration.
        List<CaseEntry> entries = caseEntryRepository.findByCaseIdOrderBySkinIdAsc(caseId);
        if (entries.isEmpty()) {
            throw new ApiException(HttpStatus.INTERNAL_SERVER_ERROR, "Case has no drop entries: " + caseId);
        }

        // Keep only entries pointing at a present, active skin.
        Map<String, Skin> skinsById = skinRepository
                .findAllById(entries.stream().map(CaseEntry::getSkinId).distinct().toList())
                .stream()
                .collect(Collectors.toMap(Skin::getId, Function.identity()));
        List<CaseEntry> candidates = entries.stream()
                .filter(entry -> {
                    Skin skin = skinsById.get(entry.getSkinId());
                    return skin != null && skin.isActive();
                })
                .toList();
        if (candidates.isEmpty()) {
            throw new ApiException(HttpStatus.INTERNAL_SERVER_ERROR,
                    "Case has no valid (active) drop entries: " + caseId);
        }

        long entryCost = (long) caseDef.getPriceVp() * rounds;

        // 4. Roll all skins in memory (DropSelector reuse) and compute totals.
        long[] totals = new long[participantCount];
        List<List<Skin>> rolledByParticipant = new ArrayList<>(participantCount);
        for (int p = 0; p < participantCount; p++) {
            List<Skin> rolls = new ArrayList<>(rounds);
            for (int r = 0; r < rounds; r++) {
                CaseEntry entry = dropSelector.selectWeighted(candidates);
                Skin skin = skinsById.get(entry.getSkinId());
                rolls.add(skin);
                totals[p] += skin.getVpValue();
            }
            rolledByParticipant.add(rolls);
        }

        int winnerIndex = battleResolver.winningIndex(totals);
        boolean userWon = winnerIndex == 0;

        // 5. Record the battle header (stable id for the debit reference and FKs).
        Battle battle = new Battle();
        battle.setAccountId(accountId);
        battle.setCaseId(caseId);
        battle.setRounds(rounds);
        battle.setParticipantCount(participantCount);
        battle.setEntryCost(entryCost);
        battle.setWinnerIndex(winnerIndex);
        battle.setUserWon(userWon);
        battle.setCreatedAt(Instant.now());
        battle = battleRepository.saveAndFlush(battle);
        UUID battleId = battle.getId();

        // 6. Charge the entry cost (a free case skips the debit).
        long newVpBalance;
        if (entryCost > 0) {
            newVpBalance = walletService.debit(accountId, entryCost, REASON_BATTLE_ENTRY, battleId).getVpBalance();
        } else {
            newVpBalance = walletService.getWalletForAccount(accountId).vpBalance();
        }

        // 7. Persist participants.
        List<BattleParticipant> participants = new ArrayList<>(participantCount);
        for (int p = 0; p < participantCount; p++) {
            BattleParticipant participant = new BattleParticipant();
            participant.setBattleId(battleId);
            participant.setParticipantIndex(p);
            participant.setUser(p == 0);
            participant.setName(p == 0 ? null : "Bot " + p);
            participant.setTotalVp(totals[p]);
            participants.add(participant);
        }
        battleParticipantRepository.saveAll(participants);

        // 8. Persist rolls.
        List<BattleRoll> rolls = new ArrayList<>(participantCount * rounds);
        for (int p = 0; p < participantCount; p++) {
            List<Skin> participantRolls = rolledByParticipant.get(p);
            for (int r = 0; r < rounds; r++) {
                Skin skin = participantRolls.get(r);
                BattleRoll roll = new BattleRoll();
                roll.setBattleId(battleId);
                roll.setParticipantIndex(p);
                roll.setRoundNumber(r + 1);
                roll.setSkinId(skin.getId());
                roll.setVpValue(skin.getVpValue());
                rolls.add(roll);
            }
        }
        battleRollRepository.saveAll(rolls);

        // 9. Grant every rolled skin to the user only on a win.
        List<String> grantedInventoryItemIds = new ArrayList<>();
        if (userWon) {
            for (BattleRoll roll : rolls) {
                InventoryItem granted = inventoryService.addItem(
                        accountId, roll.getSkinId(), INVENTORY_SOURCE_BATTLE_REWARD, null);
                roll.setGrantedInventoryItemId(granted.getId());
                grantedInventoryItemIds.add(granted.getId().toString());
            }
            eventPublisher.publishEvent(new MissionProgressEvent(accountId, MissionEventTypes.BATTLE_WON, 1));
        }

        // 10. Build the response from the in-memory rolls.
        List<BattleParticipantResponse> participantResponses = new ArrayList<>(participantCount);
        for (int p = 0; p < participantCount; p++) {
            List<RolledSkinResponse> roundSkins = rolledByParticipant.get(p).stream()
                    .map(BotBattleService::toRolledSkin)
                    .toList();
            participantResponses.add(new BattleParticipantResponse(
                    p, p == 0, p == 0 ? null : "Bot " + p, totals[p], roundSkins));
        }

        return new BattleResultResponse(
                battleId.toString(),
                caseId,
                rounds,
                entryCost,
                newVpBalance,
                winnerIndex,
                userWon,
                grantedInventoryItemIds,
                participantResponses
        );
    }

    private static RolledSkinResponse toRolledSkin(Skin skin) {
        return new RolledSkinResponse(
                skin.getId(),
                skin.getDisplayName(),
                skin.getWeapon(),
                skin.getRarity(),
                skin.getVpValue(),
                skin.getImageRef()
        );
    }
}
