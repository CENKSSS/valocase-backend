package com.cenk.valocase.battle.service;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

import org.springframework.context.ApplicationEventPublisher;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.cenk.valocase.account.domain.Account;
import com.cenk.valocase.account.repository.AccountRepository;
import com.cenk.valocase.battle.domain.Battle;
import com.cenk.valocase.battle.domain.BattleLobby;
import com.cenk.valocase.battle.domain.BattleLobbySlot;
import com.cenk.valocase.battle.domain.BattleParticipant;
import com.cenk.valocase.battle.domain.BattleRoll;
import com.cenk.valocase.battle.domain.LobbyStatus;
import com.cenk.valocase.battle.domain.SlotType;
import com.cenk.valocase.battle.dto.LobbyCreatorResponse;
import com.cenk.valocase.battle.dto.LobbyResponse;
import com.cenk.valocase.battle.dto.LobbySlotResponse;
import com.cenk.valocase.battle.dto.RolledSkinResponse;
import com.cenk.valocase.battle.repository.BattleLobbyRepository;
import com.cenk.valocase.battle.repository.BattleLobbySlotRepository;
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
import com.cenk.valocase.progression.CategoryLockedException;
import com.cenk.valocase.progression.domain.CaseCategory;
import com.cenk.valocase.progression.service.ProgressionService;
import com.cenk.valocase.wallet.service.WalletService;

import lombok.RequiredArgsConstructor;

/**
 * Public online battle lobbies. Real players create and join lobbies; the
 * creator can manually add bots to empty slots after a short delay. When every
 * slot is filled the battle resolves using the <em>existing</em> battle rules —
 * the same {@link DropSelector} roll, {@link BattleResolver} winner calculation,
 * winner-takes-all reward grant, entry-cost formula and level-lock checks used by
 * {@link BotBattleService} — and the immutable outcome is persisted into the
 * existing {@link Battle}/{@link BattleParticipant}/{@link BattleRoll} tables.
 *
 * <p>None of the battle economics are changed here: entry cost is still
 * {@code casePrice x rounds}, the winner is still the highest total VP (ties to
 * the lowest index), and only the winner receives every rolled skin.
 */
@Service
@RequiredArgsConstructor
public class BattleLobbyService {

    /** Wallet reason for a real player's lobby entry charge. */
    public static final String REASON_LOBBY_ENTRY = "BATTLE_LOBBY_ENTRY";
    /** Wallet reason for refunding a cancelled lobby's entry charge. */
    public static final String REASON_LOBBY_REFUND = "BATTLE_LOBBY_REFUND";

    /** Add Bot is blocked for this long after lobby creation (shared, server-side). */
    public static final Duration ADD_BOT_DELAY = Duration.ofSeconds(3);
    /** A full lobby waits this long before the battle resolves. */
    public static final Duration START_DELAY = Duration.ofSeconds(1);

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
    private final BattleLobbyRepository lobbyRepository;
    private final BattleLobbySlotRepository slotRepository;
    private final AccountRepository accountRepository;
    private final ProgressionService progressionService;
    private final ApplicationEventPublisher eventPublisher;

    // --- Create ----------------------------------------------------------------

    @Transactional
    public LobbyResponse createLobby(UUID accountId, String caseId, int rounds, int maxSlots) {
        if (rounds < BotBattleService.ROUNDS_MIN || rounds > BotBattleService.ROUNDS_MAX) {
            throw new ApiException(HttpStatus.BAD_REQUEST,
                    "rounds must be between " + BotBattleService.ROUNDS_MIN + " and " + BotBattleService.ROUNDS_MAX);
        }
        if (maxSlots < BotBattleService.PARTICIPANTS_MIN || maxSlots > BotBattleService.PARTICIPANTS_MAX) {
            throw new ApiException(HttpStatus.BAD_REQUEST,
                    "maxSlots must be between " + BotBattleService.PARTICIPANTS_MIN
                            + " and " + BotBattleService.PARTICIPANTS_MAX);
        }
        if (caseId == null || caseId.isBlank()) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "caseId is required");
        }

        CaseDefinition caseDef = requireActiveCase(caseId);
        Account creator = requireAccount(accountId);
        // Level-lock BEFORE any charge: a locked case never mutates state.
        requireCategoryUnlocked(caseId, creator.getLevel());

        long entryCost = (long) caseDef.getPriceVp() * rounds;

        BattleLobby lobby = new BattleLobby();
        lobby.setCreatorAccountId(accountId);
        lobby.setCaseId(caseId);
        lobby.setRounds(rounds);
        lobby.setMaxSlots(maxSlots);
        lobby.setEntryCost(entryCost);
        lobby.setStatus(LobbyStatus.WAITING);
        lobby.setCreatedAt(Instant.now());
        lobby = lobbyRepository.saveAndFlush(lobby);
        UUID lobbyId = lobby.getId();

        // Slot 0 = creator (real); the rest start empty.
        List<BattleLobbySlot> slots = new ArrayList<>(maxSlots);
        BattleLobbySlot creatorSlot = new BattleLobbySlot();
        creatorSlot.setLobbyId(lobbyId);
        creatorSlot.setSlotIndex(0);
        creatorSlot.setSlotType(SlotType.REAL);
        creatorSlot.setAccountId(accountId);
        creatorSlot.setDisplayName(creator.getDisplayName());
        creatorSlot.setCreator(true);
        creatorSlot.setChargedVp(entryCost);
        slots.add(creatorSlot);
        for (int i = 1; i < maxSlots; i++) {
            BattleLobbySlot empty = new BattleLobbySlot();
            empty.setLobbyId(lobbyId);
            empty.setSlotIndex(i);
            empty.setSlotType(SlotType.EMPTY);
            empty.setCreator(false);
            empty.setChargedVp(0L);
            slots.add(empty);
        }
        slotRepository.saveAll(slots);

        // Charge the creator. Insufficient funds (422) rolls back the whole
        // creation, so nothing is charged and no lobby is left behind.
        if (entryCost > 0) {
            walletService.debit(accountId, entryCost, REASON_LOBBY_ENTRY, lobbyId);
        }

        return mapLobby(lobby, slots, caseDef);
    }

    // --- List ------------------------------------------------------------------

    @Transactional(readOnly = true)
    public List<LobbyResponse> listOpenLobbies() {
        List<BattleLobby> lobbies = lobbyRepository.findByStatusOrderByCreatedAtDesc(LobbyStatus.WAITING);
        if (lobbies.isEmpty()) {
            return List.of();
        }
        Map<String, CaseDefinition> caseById = caseDefinitionRepository
                .findAllById(lobbies.stream().map(BattleLobby::getCaseId).distinct().toList())
                .stream().collect(Collectors.toMap(CaseDefinition::getId, Function.identity()));

        List<LobbyResponse> out = new ArrayList<>(lobbies.size());
        for (BattleLobby lobby : lobbies) {
            List<BattleLobbySlot> slots = slotRepository.findByLobbyIdOrderBySlotIndexAsc(lobby.getId());
            out.add(mapLobby(lobby, slots, caseById.get(lobby.getCaseId())));
        }
        return out;
    }

    // --- Status / poll (also triggers the delayed start) -----------------------

    @Transactional
    public LobbyResponse getLobby(UUID lobbyId) {
        BattleLobby lobby = lobbyRepository.findByIdForUpdate(lobbyId)
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "Lobby not found: " + lobbyId));
        List<BattleLobbySlot> slots = slotRepository.findByLobbyIdOrderBySlotIndexAsc(lobbyId);

        // Authoritative delayed start: once the lobby is full and the 1-second
        // readyAt has passed, resolve on this poll. The row lock makes this safe
        // against concurrent polls and prevents a double resolution.
        if (lobby.getStatus() == LobbyStatus.STARTING
                && lobby.getReadyAt() != null
                && !Instant.now().isBefore(lobby.getReadyAt())) {
            resolve(lobby, slots);
        }

        return mapLobby(lobby, slots, caseDefinitionRepository.findById(lobby.getCaseId()).orElse(null));
    }

    // --- Join ------------------------------------------------------------------

    @Transactional
    public LobbyResponse joinLobby(UUID accountId, UUID lobbyId) {
        BattleLobby lobby = lobbyRepository.findByIdForUpdate(lobbyId)
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "Lobby not found: " + lobbyId));
        if (lobby.getStatus() != LobbyStatus.WAITING) {
            throw new ApiException(HttpStatus.CONFLICT, "Lobby is not open for joining");
        }
        if (lobby.getCreatorAccountId().equals(accountId)) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "You cannot join your own lobby");
        }
        if (slotRepository.existsByLobbyIdAndAccountId(lobbyId, accountId)) {
            throw new ApiException(HttpStatus.CONFLICT, "You have already joined this lobby");
        }

        Account joiner = requireAccount(accountId);
        // Level-lock BEFORE any charge.
        requireCategoryUnlocked(lobby.getCaseId(), joiner.getLevel());

        List<BattleLobbySlot> slots = slotRepository.findByLobbyIdOrderBySlotIndexAsc(lobbyId);
        BattleLobbySlot target = firstEmptySlot(slots)
                .orElseThrow(() -> new ApiException(HttpStatus.CONFLICT, "Lobby is already full"));

        long entryCost = lobby.getEntryCost();
        if (entryCost > 0) {
            walletService.debit(accountId, entryCost, REASON_LOBBY_ENTRY, lobbyId);
        }

        target.setSlotType(SlotType.REAL);
        target.setAccountId(accountId);
        target.setDisplayName(joiner.getDisplayName());
        target.setChargedVp(entryCost);
        slotRepository.save(target);

        markStartingIfFull(lobby, slots);
        return mapLobby(lobby, slots, caseDefinitionRepository.findById(lobby.getCaseId()).orElse(null));
    }

    // --- Add Bot ---------------------------------------------------------------

    @Transactional
    public LobbyResponse addBot(UUID accountId, UUID lobbyId) {
        BattleLobby lobby = lobbyRepository.findByIdForUpdate(lobbyId)
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "Lobby not found: " + lobbyId));
        if (!lobby.getCreatorAccountId().equals(accountId)) {
            throw new ApiException(HttpStatus.FORBIDDEN, "Only the lobby creator can add bots");
        }
        if (lobby.getStatus() != LobbyStatus.WAITING) {
            throw new ApiException(HttpStatus.CONFLICT, "Bots can only be added while the lobby is waiting");
        }
        if (Instant.now().isBefore(addBotAvailableAt(lobby))) {
            throw new ApiException(HttpStatus.CONFLICT, "Add Bot is not available yet");
        }

        List<BattleLobbySlot> slots = slotRepository.findByLobbyIdOrderBySlotIndexAsc(lobbyId);
        BattleLobbySlot target = firstEmptySlot(slots)
                .orElseThrow(() -> new ApiException(HttpStatus.CONFLICT, "Lobby is already full"));

        // One click fills exactly one empty slot with one bot. Bots pay no VP.
        target.setSlotType(SlotType.BOT);
        target.setAccountId(null);
        target.setDisplayName("Bot " + target.getSlotIndex());
        target.setChargedVp(0L);
        slotRepository.save(target);

        markStartingIfFull(lobby, slots);
        return mapLobby(lobby, slots, caseDefinitionRepository.findById(lobby.getCaseId()).orElse(null));
    }

    // --- Cancel ----------------------------------------------------------------

    @Transactional
    public LobbyResponse cancelLobby(UUID accountId, UUID lobbyId) {
        BattleLobby lobby = lobbyRepository.findByIdForUpdate(lobbyId)
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "Lobby not found: " + lobbyId));
        if (!lobby.getCreatorAccountId().equals(accountId)) {
            throw new ApiException(HttpStatus.FORBIDDEN, "Only the lobby creator can cancel");
        }
        if (lobby.getStatus() != LobbyStatus.WAITING) {
            throw new ApiException(HttpStatus.CONFLICT, "Only a waiting lobby can be cancelled");
        }

        List<BattleLobbySlot> slots = slotRepository.findByLobbyIdOrderBySlotIndexAsc(lobbyId);
        long otherRealPlayers = slots.stream()
                .filter(s -> s.getSlotType() == SlotType.REAL && !s.isCreator())
                .count();
        if (otherRealPlayers > 0) {
            throw new ApiException(HttpStatus.CONFLICT,
                    "Lobby cannot be cancelled after another player has joined");
        }

        // Refund every real occupant's actual charge (only the creator here).
        for (BattleLobbySlot slot : slots) {
            if (slot.getSlotType() == SlotType.REAL && slot.getChargedVp() > 0 && slot.getAccountId() != null) {
                walletService.credit(slot.getAccountId(), slot.getChargedVp(), REASON_LOBBY_REFUND, lobbyId);
            }
        }

        lobby.setStatus(LobbyStatus.CANCELLED);
        lobby.setCancelledAt(Instant.now());
        lobbyRepository.save(lobby);

        return mapLobby(lobby, slots, caseDefinitionRepository.findById(lobby.getCaseId()).orElse(null));
    }

    // --- Resolution (reuses the existing battle rules) -------------------------

    /**
     * Resolves a full lobby into an immutable {@link Battle}. Rolls every slot
     * with {@link DropSelector}, picks the winner with {@link BattleResolver},
     * and grants every rolled skin to the winner — only if the winner is a real
     * player. A bot winner grants nothing (bots have no inventory or wallet).
     */
    private void resolve(BattleLobby lobby, List<BattleLobbySlot> slots) {
        if (lobby.getStatus() == LobbyStatus.COMPLETED) {
            return; // already resolved; result is immutable
        }
        requireActiveCase(lobby.getCaseId());
        List<CaseEntry> candidates = loadCandidates(lobby.getCaseId());

        Map<String, Skin> skinsById = skinRepository
                .findAllById(candidates.stream().map(CaseEntry::getSkinId).distinct().toList())
                .stream().collect(Collectors.toMap(Skin::getId, Function.identity()));

        int n = lobby.getMaxSlots();
        int rounds = lobby.getRounds();
        long[] totals = new long[n];
        List<List<Skin>> rolledByParticipant = new ArrayList<>(n);
        for (int p = 0; p < n; p++) {
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
        BattleLobbySlot winnerSlot = slots.get(winnerIndex);
        boolean creatorWon = winnerSlot.isCreator();

        // Persist the immutable battle header into the existing table.
        Battle battle = new Battle();
        battle.setAccountId(lobby.getCreatorAccountId());
        battle.setCaseId(lobby.getCaseId());
        battle.setRounds(rounds);
        battle.setParticipantCount(n);
        battle.setEntryCost(lobby.getEntryCost());
        battle.setWinnerIndex(winnerIndex);
        battle.setUserWon(creatorWon);
        battle.setCreatedAt(Instant.now());
        battle = battleRepository.saveAndFlush(battle);
        UUID battleId = battle.getId();

        List<BattleParticipant> participants = new ArrayList<>(n);
        for (int p = 0; p < n; p++) {
            BattleLobbySlot slot = slots.get(p);
            BattleParticipant participant = new BattleParticipant();
            participant.setBattleId(battleId);
            participant.setParticipantIndex(p);
            participant.setUser(slot.getSlotType() == SlotType.REAL);
            participant.setName(slot.getDisplayName());
            participant.setTotalVp(totals[p]);
            participants.add(participant);
        }
        battleParticipantRepository.saveAll(participants);

        List<BattleRoll> rolls = new ArrayList<>(n * rounds);
        for (int p = 0; p < n; p++) {
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

        // Winner-takes-all: grant every rolled skin to the winner, but only when
        // the winner is a real player. Same reward logic as the bot battle.
        if (winnerSlot.getSlotType() == SlotType.REAL && winnerSlot.getAccountId() != null) {
            UUID winnerAccount = winnerSlot.getAccountId();
            for (BattleRoll roll : rolls) {
                InventoryItem granted = inventoryService.addItem(
                        winnerAccount, roll.getSkinId(), BotBattleService.INVENTORY_SOURCE_BATTLE_REWARD, null);
                roll.setGrantedInventoryItemId(granted.getId());
            }
            eventPublisher.publishEvent(
                    new MissionProgressEvent(winnerAccount, MissionEventTypes.BATTLE_WON, 1));
        }
        battleRollRepository.saveAll(rolls);

        lobby.setStatus(LobbyStatus.COMPLETED);
        lobby.setWinnerSlotIndex(winnerIndex);
        lobby.setResultBattleId(battleId);
        lobby.setCompletedAt(Instant.now());
        lobbyRepository.save(lobby);
    }

    // --- Helpers ---------------------------------------------------------------

    private void markStartingIfFull(BattleLobby lobby, List<BattleLobbySlot> slots) {
        boolean full = slots.stream().noneMatch(s -> s.getSlotType() == SlotType.EMPTY);
        if (full) {
            Instant now = Instant.now();
            lobby.setStatus(LobbyStatus.STARTING);
            lobby.setStartedAt(now);
            lobby.setReadyAt(now.plus(START_DELAY));
            lobbyRepository.save(lobby);
        }
    }

    private java.util.Optional<BattleLobbySlot> firstEmptySlot(List<BattleLobbySlot> slots) {
        return slots.stream()
                .filter(s -> s.getSlotType() == SlotType.EMPTY)
                .min(Comparator.comparingInt(BattleLobbySlot::getSlotIndex));
    }

    private Instant addBotAvailableAt(BattleLobby lobby) {
        return lobby.getCreatedAt().plus(ADD_BOT_DELAY);
    }

    private CaseDefinition requireActiveCase(String caseId) {
        CaseDefinition caseDef = caseDefinitionRepository.findById(caseId)
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "Case not found: " + caseId));
        if (!caseDef.isActive()) {
            throw new ApiException(HttpStatus.NOT_FOUND, "Case is not available: " + caseId);
        }
        return caseDef;
    }

    private List<CaseEntry> loadCandidates(String caseId) {
        List<CaseEntry> entries = caseEntryRepository.findByCaseIdOrderBySkinIdAsc(caseId);
        if (entries.isEmpty()) {
            throw new ApiException(HttpStatus.INTERNAL_SERVER_ERROR, "Case has no drop entries: " + caseId);
        }
        Map<String, Skin> skinsById = skinRepository
                .findAllById(entries.stream().map(CaseEntry::getSkinId).distinct().toList())
                .stream().collect(Collectors.toMap(Skin::getId, Function.identity()));
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
        return candidates;
    }

    private void requireCategoryUnlocked(String caseId, int level) {
        CaseCategory.fromCaseId(caseId).ifPresent(category -> {
            if (!progressionService.isCategoryUnlocked(level, category)) {
                throw new CategoryLockedException(category, level);
            }
        });
    }

    private Account requireAccount(UUID accountId) {
        return accountRepository.findById(accountId)
                .orElseThrow(() -> new ApiException(HttpStatus.UNAUTHORIZED, "Account not found: " + accountId));
    }

    // --- Mapping ---------------------------------------------------------------

    private LobbyResponse mapLobby(BattleLobby lobby, List<BattleLobbySlot> slots, CaseDefinition caseDef) {
        Instant now = Instant.now();
        Instant addBotAt = addBotAvailableAt(lobby);
        boolean addBotWindowOpen = lobby.getStatus() == LobbyStatus.WAITING && !now.isBefore(addBotAt);

        int filled = (int) slots.stream().filter(s -> s.getSlotType() != SlotType.EMPTY).count();

        // For a completed lobby, attach the persisted rolls/totals per slot.
        Map<Integer, BattleParticipant> participantByIndex = Map.of();
        Map<Integer, List<RolledSkinResponse>> rollsByIndex = Map.of();
        if (lobby.getStatus() == LobbyStatus.COMPLETED && lobby.getResultBattleId() != null) {
            UUID battleId = lobby.getResultBattleId();
            participantByIndex = battleParticipantRepository
                    .findByBattleIdOrderByParticipantIndexAsc(battleId).stream()
                    .collect(Collectors.toMap(BattleParticipant::getParticipantIndex, Function.identity()));
            rollsByIndex = buildRollsByIndex(battleId);
        }

        List<LobbySlotResponse> slotResponses = new ArrayList<>(slots.size());
        String winnerDisplayName = null;
        for (BattleLobbySlot slot : slots) {
            boolean addBotAllowed = slot.getSlotType() == SlotType.EMPTY && addBotWindowOpen;
            BattleParticipant participant = participantByIndex.get(slot.getSlotIndex());
            Long totalVp = participant != null ? participant.getTotalVp() : null;
            List<RolledSkinResponse> rounds = rollsByIndex.get(slot.getSlotIndex());
            slotResponses.add(new LobbySlotResponse(
                    slot.getSlotIndex(),
                    slot.getSlotType().name(),
                    slot.getAccountId() != null ? slot.getAccountId().toString() : null,
                    slot.getDisplayName(),
                    slot.isCreator(),
                    addBotAllowed,
                    totalVp,
                    rounds
            ));
            if (lobby.getWinnerSlotIndex() != null && lobby.getWinnerSlotIndex() == slot.getSlotIndex()) {
                winnerDisplayName = slot.getDisplayName();
            }
        }

        String creatorDisplayName = slots.stream()
                .filter(BattleLobbySlot::isCreator)
                .findFirst()
                .map(BattleLobbySlot::getDisplayName)
                .orElse(null);

        return new LobbyResponse(
                lobby.getId().toString(),
                lobby.getStatus().name(),
                new LobbyCreatorResponse(lobby.getCreatorAccountId().toString(), creatorDisplayName),
                lobby.getCaseId(),
                caseDef != null ? caseDef.getDisplayName() : null,
                lobby.getRounds(),
                lobby.getEntryCost(),
                lobby.getMaxSlots(),
                filled,
                slotResponses,
                lobby.getCreatedAt(),
                addBotAt,
                addBotWindowOpen,
                lobby.getReadyAt(),
                lobby.getWinnerSlotIndex(),
                winnerDisplayName
        );
    }

    private Map<Integer, List<RolledSkinResponse>> buildRollsByIndex(UUID battleId) {
        List<BattleRoll> rolls = battleRollRepository.findByBattleId(battleId);
        if (rolls.isEmpty()) {
            return Map.of();
        }
        Map<String, Skin> skinsById = skinRepository
                .findAllById(rolls.stream().map(BattleRoll::getSkinId).distinct().toList())
                .stream().collect(Collectors.toMap(Skin::getId, Function.identity()));

        Map<Integer, List<BattleRoll>> grouped = rolls.stream()
                .sorted(Comparator.comparingInt(BattleRoll::getRoundNumber))
                .collect(Collectors.groupingBy(BattleRoll::getParticipantIndex));

        Map<Integer, List<RolledSkinResponse>> out = new java.util.HashMap<>();
        grouped.forEach((index, list) -> {
            List<RolledSkinResponse> mapped = list.stream()
                    .map(roll -> toRolledSkin(roll, skinsById.get(roll.getSkinId())))
                    .toList();
            out.put(index, mapped);
        });
        return out;
    }

    private static RolledSkinResponse toRolledSkin(BattleRoll roll, Skin skin) {
        if (skin == null) {
            return new RolledSkinResponse(roll.getSkinId(), null, null, null, roll.getVpValue(), null);
        }
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
