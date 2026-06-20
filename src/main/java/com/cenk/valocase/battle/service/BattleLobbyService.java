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
import com.cenk.valocase.account.service.AccountService;
import com.cenk.valocase.battle.domain.Battle;
import com.cenk.valocase.battle.domain.BattleLobby;
import com.cenk.valocase.battle.domain.BattleLobbyCase;
import com.cenk.valocase.battle.domain.BattleLobbySlot;
import com.cenk.valocase.battle.domain.BattleParticipant;
import com.cenk.valocase.battle.domain.BattleRoll;
import com.cenk.valocase.battle.domain.LobbyStatus;
import com.cenk.valocase.battle.domain.SlotType;
import com.cenk.valocase.battle.dto.CaseSelectionRequest;
import com.cenk.valocase.battle.dto.CaseSelectionResponse;
import com.cenk.valocase.battle.dto.LobbyCreatorResponse;
import com.cenk.valocase.battle.dto.LobbyResponse;
import com.cenk.valocase.battle.dto.LobbySlotResponse;
import com.cenk.valocase.battle.dto.RolledSkinResponse;
import com.cenk.valocase.battle.repository.BattleLobbyCaseRepository;
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
import lombok.extern.slf4j.Slf4j;

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
@Slf4j
public class BattleLobbyService {

    /** Wallet reason for a real player's lobby entry charge. */
    public static final String REASON_LOBBY_ENTRY = "BATTLE_LOBBY_ENTRY";
    /** Wallet reason for refunding a cancelled lobby's entry charge. */
    public static final String REASON_LOBBY_REFUND = "BATTLE_LOBBY_REFUND";

    /** Flat XP granted to each real participant once a battle completes (per battle, not per round). */
    public static final int PVP_BATTLE_XP = 5;

    /** Add Bot is blocked for this long after lobby creation (shared, server-side). */
    public static final Duration ADD_BOT_DELAY = Duration.ofSeconds(3);
    /** A full lobby waits this long before the battle resolves. */
    public static final Duration START_DELAY = Duration.ofSeconds(1);
    /**
     * A WAITING lobby that has not started within this window expires: the
     * cleanup job cancels it, refunds every real participant once, and it stops
     * appearing in the public list.
     */
    public static final Duration LOBBY_TIMEOUT = Duration.ofMinutes(2);
    /**
     * A real-player slot is "connected" if it was seen within this window. A
     * winner that is not connected at resolution receives no reward.
     */
    public static final Duration CONNECTION_TIMEOUT = Duration.ofSeconds(15);

    /** A lobby may select at most this many distinct cases. */
    public static final int MAX_CASE_TYPES = 5;

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
    private final BattleLobbyCaseRepository lobbyCaseRepository;
    private final BattleLobbySlotRepository slotRepository;
    private final AccountRepository accountRepository;
    private final ProgressionService progressionService;
    private final ApplicationEventPublisher eventPublisher;

    // --- Create ----------------------------------------------------------------

    @Transactional
    public LobbyResponse createLobby(UUID accountId, List<CaseSelectionRequest> selections, Integer maxSlots) {
        if (maxSlots == null
                || maxSlots < BotBattleService.PARTICIPANTS_MIN || maxSlots > BotBattleService.PARTICIPANTS_MAX) {
            throw new ApiException(HttpStatus.BAD_REQUEST,
                    "maxSlots must be between " + BotBattleService.PARTICIPANTS_MIN
                            + " and " + BotBattleService.PARTICIPANTS_MAX);
        }
        if (selections == null || selections.isEmpty()) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "At least one case must be selected");
        }
        if (selections.size() > MAX_CASE_TYPES) {
            throw new ApiException(HttpStatus.BAD_REQUEST,
                    "At most " + MAX_CASE_TYPES + " different cases can be selected");
        }

        Account creator = requireAccount(accountId);

        // Validate every selection and resolve its case BEFORE any charge: a
        // locked / missing / invalid case never mutates state.
        Map<String, CaseDefinition> caseById = new java.util.LinkedHashMap<>();
        long entryCost = 0;
        int totalRounds = 0;
        for (CaseSelectionRequest selection : selections) {
            String caseId = selection.caseId();
            Integer quantity = selection.quantity();
            if (caseId == null || caseId.isBlank()) {
                throw new ApiException(HttpStatus.BAD_REQUEST, "caseId is required for every selection");
            }
            if (quantity == null
                    || quantity < BotBattleService.ROUNDS_MIN || quantity > BotBattleService.ROUNDS_MAX) {
                throw new ApiException(HttpStatus.BAD_REQUEST,
                        "quantity must be between " + BotBattleService.ROUNDS_MIN
                                + " and " + BotBattleService.ROUNDS_MAX + " for case " + caseId);
            }
            if (caseById.containsKey(caseId)) {
                throw new ApiException(HttpStatus.BAD_REQUEST, "Duplicate case selected: " + caseId);
            }
            CaseDefinition caseDef = requireActiveCase(caseId);
            requireCategoryUnlocked(caseId, creator.getLevel());
            caseById.put(caseId, caseDef);
            entryCost += (long) caseDef.getPriceVp() * quantity;
            totalRounds += quantity;
        }

        String primaryCaseId = selections.get(0).caseId();

        BattleLobby lobby = new BattleLobby();
        lobby.setCreatorAccountId(accountId);
        lobby.setCaseId(primaryCaseId);
        lobby.setRounds(totalRounds);
        lobby.setMaxSlots(maxSlots);
        lobby.setEntryCost(entryCost);
        lobby.setStatus(LobbyStatus.WAITING);
        lobby.setCreatedAt(Instant.now());
        lobby = lobbyRepository.saveAndFlush(lobby);
        UUID lobbyId = lobby.getId();

        List<BattleLobbyCase> lobbyCases = new ArrayList<>(selections.size());
        for (int i = 0; i < selections.size(); i++) {
            CaseSelectionRequest selection = selections.get(i);
            BattleLobbyCase lobbyCase = new BattleLobbyCase();
            lobbyCase.setLobbyId(lobbyId);
            lobbyCase.setOrdinal(i);
            lobbyCase.setCaseId(selection.caseId());
            lobbyCase.setQuantity(selection.quantity());
            lobbyCases.add(lobbyCase);
        }
        lobbyCaseRepository.saveAll(lobbyCases);

        // Slot 0 = creator (real); the rest start empty.
        List<BattleLobbySlot> slots = new ArrayList<>(maxSlots);
        BattleLobbySlot creatorSlot = new BattleLobbySlot();
        creatorSlot.setLobbyId(lobbyId);
        creatorSlot.setSlotIndex(0);
        creatorSlot.setSlotType(SlotType.REAL);
        creatorSlot.setAccountId(accountId);
        creatorSlot.setDisplayName(creator.getDisplayName());
        creatorSlot.setAvatarId(creator.getAvatarId());
        creatorSlot.setCreator(true);
        creatorSlot.setChargedVp(entryCost);
        creatorSlot.setLastSeenAt(Instant.now());
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

        return mapLobby(lobby, slots, lobbyCases, caseById, accountId);
    }

    // --- List ------------------------------------------------------------------

    @Transactional(readOnly = true)
    public List<LobbyResponse> listOpenLobbies(UUID viewerAccountId) {
        List<BattleLobby> lobbies = lobbyRepository.findByStatusOrderByCreatedAtDesc(LobbyStatus.WAITING);
        log.info("LOBBY_DEBUG list: viewer={} fetchedWaiting={} ids={}",
                viewerAccountId, lobbies.size(), lobbies.stream().map(BattleLobby::getId).toList());
        if (lobbies.isEmpty()) {
            return List.of();
        }
        Instant expiryCutoff = Instant.now().minus(LOBBY_TIMEOUT);
        Map<UUID, List<BattleLobbyCase>> casesByLobby = lobbyCaseRepository
                .findByLobbyIdInOrderByOrdinalAsc(lobbies.stream().map(BattleLobby::getId).toList())
                .stream().collect(Collectors.groupingBy(BattleLobbyCase::getLobbyId));

        List<String> caseIds = new ArrayList<>();
        lobbies.forEach(l -> caseIds.add(l.getCaseId()));
        casesByLobby.values().forEach(list -> list.forEach(c -> caseIds.add(c.getCaseId())));
        Map<String, CaseDefinition> caseById = caseDefinitionRepository
                .findAllById(caseIds.stream().distinct().toList())
                .stream().collect(Collectors.toMap(CaseDefinition::getId, Function.identity()));

        List<LobbyResponse> out = new ArrayList<>(lobbies.size());
        for (BattleLobby lobby : lobbies) {
            if (lobby.getCreatedAt().isBefore(expiryCutoff)) {
                log.info("LOBBY_DEBUG list: skipping expired lobbyId={} createdAt={}",
                        lobby.getId(), lobby.getCreatedAt());
                continue;
            }
            List<BattleLobbySlot> slots = slotRepository.findByLobbyIdOrderBySlotIndexAsc(lobby.getId());
            List<BattleLobbyCase> lobbyCases = casesByLobby.getOrDefault(lobby.getId(), List.of());
            out.add(mapLobby(lobby, slots, lobbyCases, caseById, viewerAccountId));
        }
        log.info("LOBBY_DEBUG list: viewer={} returned={}", viewerAccountId, out.size());
        return out;
    }

    // --- Status / poll (also triggers the delayed start) -----------------------

    @Transactional
    public LobbyResponse getLobby(UUID viewerAccountId, UUID lobbyId) {
        BattleLobby lobby = lobbyRepository.findByIdForUpdate(lobbyId)
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "Lobby not found: " + lobbyId));
        List<BattleLobbySlot> slots = slotRepository.findByLobbyIdOrderBySlotIndexAsc(lobbyId);

        // Heartbeat: a committed real player polling their lobby is "connected".
        // Done before resolution so a winner actively waiting for the result is
        // counted as connected. Only meaningful while the lobby is open/starting.
        if (viewerAccountId != null
                && (lobby.getStatus() == LobbyStatus.WAITING || lobby.getStatus() == LobbyStatus.STARTING)) {
            for (BattleLobbySlot slot : slots) {
                if (slot.getSlotType() == SlotType.REAL && viewerAccountId.equals(slot.getAccountId())) {
                    slot.setLastSeenAt(Instant.now());
                    slotRepository.save(slot);
                }
            }
        }

        // Authoritative delayed start: once the lobby is full and the 1-second
        // readyAt has passed, resolve on this poll. The row lock makes this safe
        // against concurrent polls and prevents a double resolution.
        if (lobby.getStatus() == LobbyStatus.STARTING
                && lobby.getReadyAt() != null
                && !Instant.now().isBefore(lobby.getReadyAt())) {
            resolve(lobby, slots);
        }

        return mapLobby(lobby, slots, viewerAccountId);
    }

    // --- Join ------------------------------------------------------------------

    @Transactional
    public LobbyResponse joinLobby(UUID accountId, UUID lobbyId, int slotIndex) {
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

        // Joining is intentionally NOT level-locked: only the creator's level
        // controls which cases a lobby may use. Anyone can join an open lobby.
        Account joiner = requireAccount(accountId);

        List<BattleLobbySlot> slots = slotRepository.findByLobbyIdOrderBySlotIndexAsc(lobbyId);
        BattleLobbySlot target = slots.stream()
                .filter(s -> s.getSlotIndex() == slotIndex)
                .findFirst()
                .orElseThrow(() -> new ApiException(HttpStatus.BAD_REQUEST, "No such slot: " + slotIndex));
        if (target.getSlotType() != SlotType.EMPTY) {
            throw new ApiException(HttpStatus.CONFLICT, "Slot " + slotIndex + " is already taken");
        }

        long entryCost = lobby.getEntryCost();
        if (entryCost > 0) {
            walletService.debit(accountId, entryCost, REASON_LOBBY_ENTRY, lobbyId);
        }

        target.setSlotType(SlotType.REAL);
        target.setAccountId(accountId);
        target.setDisplayName(joiner.getDisplayName());
        target.setAvatarId(joiner.getAvatarId());
        target.setChargedVp(entryCost);
        target.setLastSeenAt(Instant.now());
        slotRepository.save(target);

        markStartingIfFull(lobby, slots);
        return mapLobby(lobby, slots, accountId);
    }

    // --- Leave (only while WAITING; frees the slot and refunds once) -----------

    @Transactional
    public LobbyResponse leaveLobby(UUID accountId, UUID lobbyId) {
        BattleLobby lobby = lobbyRepository.findByIdForUpdate(lobbyId)
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "Lobby not found: " + lobbyId));
        if (lobby.getStatus() != LobbyStatus.WAITING) {
            throw new ApiException(HttpStatus.CONFLICT, "You can only leave while the lobby is waiting");
        }

        List<BattleLobbySlot> slots = slotRepository.findByLobbyIdOrderBySlotIndexAsc(lobbyId);
        BattleLobbySlot seat = slots.stream()
                .filter(s -> s.getSlotType() == SlotType.REAL && accountId.equals(s.getAccountId()))
                .findFirst()
                .orElse(null);
        if (seat == null) {
            return mapLobby(lobby, slots, accountId);
        }
        if (seat.isCreator()) {
            throw new ApiException(HttpStatus.CONFLICT, "The host cannot leave; the lobby expires if it never starts");
        }

        if (seat.getChargedVp() > 0) {
            walletService.credit(accountId, seat.getChargedVp(), REASON_LOBBY_REFUND, lobbyId);
        }
        seat.setSlotType(SlotType.EMPTY);
        seat.setAccountId(null);
        seat.setDisplayName(null);
        seat.setAvatarId(null);
        seat.setChargedVp(0L);
        seat.setLastSeenAt(null);
        slotRepository.save(seat);

        return mapLobby(lobby, slots, accountId);
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
        target.setAvatarId(AccountService.DEFAULT_AVATAR_ID);
        target.setChargedVp(0L);
        slotRepository.save(target);

        markStartingIfFull(lobby, slots);
        return mapLobby(lobby, slots, accountId);
    }

    // --- Maintenance: expiry cleanup + fallback start --------------------------

    /** Ids of WAITING lobbies that have passed the timeout and should be cancelled. */
    @Transactional(readOnly = true)
    public List<UUID> staleWaitingLobbyIds() {
        Instant cutoff = Instant.now().minus(LOBBY_TIMEOUT);
        return lobbyRepository.findByStatusAndCreatedAtBefore(LobbyStatus.WAITING, cutoff)
                .stream().map(BattleLobby::getId).toList();
    }

    /**
     * Cancels a single expired WAITING lobby and refunds every real participant
     * once. Re-checks status and the timeout under the row lock, so it never
     * double-refunds and never races a start.
     */
    @Transactional
    public void cancelStaleLobby(UUID lobbyId) {
        BattleLobby lobby = lobbyRepository.findByIdForUpdate(lobbyId).orElse(null);
        if (lobby == null || lobby.getStatus() != LobbyStatus.WAITING) {
            return;
        }
        if (lobby.getCreatedAt().isAfter(Instant.now().minus(LOBBY_TIMEOUT))) {
            return;
        }
        log.info("LOBBY_DEBUG scheduler cancel: lobbyId={} status={} createdAt={}",
                lobbyId, lobby.getStatus(), lobby.getCreatedAt());
        List<BattleLobbySlot> slots = slotRepository.findByLobbyIdOrderBySlotIndexAsc(lobbyId);
        refundRealOccupants(lobby, slots);
    }

    /** Ids of full (STARTING) lobbies whose start delay elapsed but were never polled. */
    @Transactional(readOnly = true)
    public List<UUID> dueStartingLobbyIds() {
        return lobbyRepository.findByStatusAndReadyAtLessThanEqual(LobbyStatus.STARTING, Instant.now())
                .stream().map(BattleLobby::getId).toList();
    }

    /**
     * Fallback resolution for a full lobby that no client polled. Same locked,
     * status-guarded resolution as the status endpoint, so it is idempotent and
     * never starts a battle that is not full or already resolved.
     */
    @Transactional
    public void resolveDueLobby(UUID lobbyId) {
        BattleLobby lobby = lobbyRepository.findByIdForUpdate(lobbyId).orElse(null);
        if (lobby == null || lobby.getStatus() != LobbyStatus.STARTING) {
            return;
        }
        if (lobby.getReadyAt() == null || Instant.now().isBefore(lobby.getReadyAt())) {
            return;
        }
        resolve(lobby, slotRepository.findByLobbyIdOrderBySlotIndexAsc(lobbyId));
    }

    /** Refunds each real occupant's actual charge and marks the lobby CANCELLED. */
    private void refundRealOccupants(BattleLobby lobby, List<BattleLobbySlot> slots) {
        for (BattleLobbySlot slot : slots) {
            if (slot.getSlotType() == SlotType.REAL && slot.getChargedVp() > 0 && slot.getAccountId() != null) {
                walletService.credit(slot.getAccountId(), slot.getChargedVp(), REASON_LOBBY_REFUND, lobby.getId());
            }
        }
        lobby.setStatus(LobbyStatus.CANCELLED);
        lobby.setCancelledAt(Instant.now());
        lobbyRepository.save(lobby);
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
        // Ordered openings across every selected case (caseId repeated by quantity).
        List<String> openings = resolveOpenings(lobby);
        Map<String, List<CaseEntry>> candidatesByCase = new java.util.HashMap<>();
        java.util.Set<String> allSkinIds = new java.util.LinkedHashSet<>();
        for (String caseId : openings.stream().distinct().toList()) {
            requireActiveCase(caseId);
            List<CaseEntry> candidates = loadCandidates(caseId);
            candidatesByCase.put(caseId, candidates);
            candidates.forEach(e -> allSkinIds.add(e.getSkinId()));
        }
        Map<String, Skin> skinsById = skinRepository
                .findAllById(allSkinIds)
                .stream().collect(Collectors.toMap(Skin::getId, Function.identity()));

        int n = lobby.getMaxSlots();
        int rounds = openings.size();
        long[] totals = new long[n];
        List<List<Skin>> rolledByParticipant = new ArrayList<>(n);
        for (int p = 0; p < n; p++) {
            List<Skin> rolls = new ArrayList<>(rounds);
            for (String caseId : openings) {
                CaseEntry entry = dropSelector.selectWeighted(candidatesByCase.get(caseId));
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
            participant.setName(slotDisplayName(slot));
            participant.setAvatarId(slotAvatarId(slot));
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

        // Winner-takes-all: every rolled skin goes to a real, connected winner.
        if (winnerSlot.getSlotType() == SlotType.REAL
                && winnerSlot.getAccountId() != null
                && isConnected(winnerSlot, Instant.now())) {
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

        grantBattleXp(slots);

        lobby.setStatus(LobbyStatus.COMPLETED);
        lobby.setWinnerSlotIndex(winnerIndex);
        lobby.setResultBattleId(battleId);
        lobby.setCompletedAt(Instant.now());
        lobbyRepository.save(lobby);
    }

    private void grantBattleXp(List<BattleLobbySlot> slots) {
        for (BattleLobbySlot slot : slots) {
            if (slot.getSlotType() == SlotType.REAL && slot.getAccountId() != null) {
                accountRepository.findById(slot.getAccountId())
                        .ifPresent(account -> progressionService.grantCaseOpenXp(account, PVP_BATTLE_XP));
                eventPublisher.publishEvent(
                        new MissionProgressEvent(slot.getAccountId(), MissionEventTypes.BATTLE_PLAYED, 1));
            }
        }
    }

    // --- Helpers ---------------------------------------------------------------

    private void markStartingIfFull(BattleLobby lobby, List<BattleLobbySlot> slots) {
        boolean full = slots.stream().noneMatch(s -> s.getSlotType() == SlotType.EMPTY);
        if (full) {
            Instant now = Instant.now();
            // Confirm presence for everyone in the lobby at start so a participant
            // who wins the prompt resolution is reward-eligible.
            slots.stream()
                    .filter(s -> s.getSlotType() == SlotType.REAL)
                    .forEach(s -> s.setLastSeenAt(now));
            slotRepository.saveAll(slots);
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

    /** Display name shown to clients: real players fall back to a stable name, bots keep "Bot N". */
    private static String slotDisplayName(BattleLobbySlot slot) {
        if (slot.getSlotType() == SlotType.REAL) {
            return AccountService.resolveDisplayName(slot.getDisplayName(), slot.getAccountId());
        }
        return slot.getDisplayName();
    }

    /** Avatar shown to clients: real/bot slots fall back to the default, empty slots have none. */
    private static String slotAvatarId(BattleLobbySlot slot) {
        if (slot.getSlotType() == SlotType.EMPTY) {
            return null;
        }
        return AccountService.resolveAvatarId(slot.getAvatarId());
    }

    /** A real slot seen within the connection window counts as connected. */
    private boolean isConnected(BattleLobbySlot slot, Instant now) {
        return slot.getLastSeenAt() != null
                && !slot.getLastSeenAt().isBefore(now.minus(CONNECTION_TIMEOUT));
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

    /** Ordered case ids opened per participant (each case repeated by its quantity). */
    private List<String> resolveOpenings(BattleLobby lobby) {
        List<BattleLobbyCase> selections = lobbyCaseRepository.findByLobbyIdOrderByOrdinalAsc(lobby.getId());
        List<String> openings = new ArrayList<>();
        if (selections.isEmpty()) {
            for (int i = 0; i < lobby.getRounds(); i++) {
                openings.add(lobby.getCaseId());
            }
        } else {
            for (BattleLobbyCase selection : selections) {
                for (int i = 0; i < selection.getQuantity(); i++) {
                    openings.add(selection.getCaseId());
                }
            }
        }
        return openings;
    }

    // --- Mapping ---------------------------------------------------------------

    private LobbyResponse mapLobby(BattleLobby lobby, List<BattleLobbySlot> slots, UUID viewerAccountId) {
        List<BattleLobbyCase> lobbyCases = lobbyCaseRepository.findByLobbyIdOrderByOrdinalAsc(lobby.getId());
        java.util.Set<String> ids = new java.util.LinkedHashSet<>();
        ids.add(lobby.getCaseId());
        lobbyCases.forEach(c -> ids.add(c.getCaseId()));
        Map<String, CaseDefinition> caseById = caseDefinitionRepository.findAllById(ids).stream()
                .collect(Collectors.toMap(CaseDefinition::getId, Function.identity()));
        return mapLobby(lobby, slots, lobbyCases, caseById, viewerAccountId);
    }

    private LobbyResponse mapLobby(BattleLobby lobby, List<BattleLobbySlot> slots,
            List<BattleLobbyCase> lobbyCases, Map<String, CaseDefinition> caseById, UUID viewerAccountId) {
        Instant now = Instant.now();
        Instant addBotAt = addBotAvailableAt(lobby);
        boolean viewerIsHost = lobby.getCreatorAccountId().equals(viewerAccountId);
        boolean addBotWindowOpen =
                viewerIsHost && lobby.getStatus() == LobbyStatus.WAITING && !now.isBefore(addBotAt);

        int filled = (int) slots.stream().filter(s -> s.getSlotType() != SlotType.EMPTY).count();

        // For a completed lobby, attach the persisted rolls/totals per slot and
        // whether the winner was actually rewarded (a granted roll means yes; a
        // bot or disconnected winner means no grant happened).
        Map<Integer, BattleParticipant> participantByIndex = Map.of();
        Map<Integer, List<RolledSkinResponse>> rollsByIndex = Map.of();
        Boolean winnerRewarded = null;
        if (lobby.getStatus() == LobbyStatus.COMPLETED && lobby.getResultBattleId() != null) {
            UUID battleId = lobby.getResultBattleId();
            participantByIndex = battleParticipantRepository
                    .findByBattleIdOrderByParticipantIndexAsc(battleId).stream()
                    .collect(Collectors.toMap(BattleParticipant::getParticipantIndex, Function.identity()));
            List<BattleRoll> battleRolls = battleRollRepository.findByBattleId(battleId);
            rollsByIndex = buildRollsByIndex(battleRolls);
            winnerRewarded = battleRolls.stream().anyMatch(r -> r.getGrantedInventoryItemId() != null);
        }

        List<LobbySlotResponse> slotResponses = new ArrayList<>(slots.size());
        String winnerDisplayName = null;
        String winnerAvatarId = null;
        for (BattleLobbySlot slot : slots) {
            boolean addBotAllowed = slot.getSlotType() == SlotType.EMPTY && addBotWindowOpen;
            boolean connected = switch (slot.getSlotType()) {
                case BOT -> true;
                case REAL -> isConnected(slot, now);
                case EMPTY -> false;
            };
            BattleParticipant participant = participantByIndex.get(slot.getSlotIndex());
            Long totalVp = participant != null ? participant.getTotalVp() : null;
            List<RolledSkinResponse> rounds = rollsByIndex.get(slot.getSlotIndex());
            String slotDisplayName = slotDisplayName(slot);
            String slotAvatarId = slotAvatarId(slot);
            slotResponses.add(new LobbySlotResponse(
                    slot.getSlotIndex(),
                    slot.getSlotType().name(),
                    slot.getAccountId() != null ? slot.getAccountId().toString() : null,
                    slotDisplayName,
                    slotAvatarId,
                    slot.isCreator(),
                    addBotAllowed,
                    connected,
                    totalVp,
                    rounds
            ));
            if (lobby.getWinnerSlotIndex() != null && lobby.getWinnerSlotIndex() == slot.getSlotIndex()) {
                winnerDisplayName = slotDisplayName;
                winnerAvatarId = slotAvatarId;
            }
        }

        BattleLobbySlot creatorSlot = slots.stream()
                .filter(BattleLobbySlot::isCreator)
                .findFirst()
                .orElse(null);
        String creatorDisplayName = creatorSlot != null ? slotDisplayName(creatorSlot) : null;
        String creatorAvatarId = creatorSlot != null ? slotAvatarId(creatorSlot) : null;

        List<CaseSelectionResponse> caseSelections = buildCaseSelections(lobby, lobbyCases, caseById);
        CaseDefinition primaryCase = caseById.get(lobby.getCaseId());

        return new LobbyResponse(
                lobby.getId().toString(),
                lobby.getStatus().name(),
                new LobbyCreatorResponse(lobby.getCreatorAccountId().toString(), creatorDisplayName, creatorAvatarId),
                lobby.getCaseId(),
                primaryCase != null ? primaryCase.getDisplayName() : null,
                caseSelections,
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
                winnerDisplayName,
                winnerAvatarId,
                winnerRewarded
        );
    }

    private List<CaseSelectionResponse> buildCaseSelections(BattleLobby lobby,
            List<BattleLobbyCase> lobbyCases, Map<String, CaseDefinition> caseById) {
        if (lobbyCases.isEmpty()) {
            CaseDefinition cd = caseById.get(lobby.getCaseId());
            return List.of(new CaseSelectionResponse(
                    lobby.getCaseId(),
                    cd != null ? cd.getDisplayName() : null,
                    lobby.getRounds(),
                    cd != null ? cd.getPriceVp() : 0L));
        }
        return lobbyCases.stream()
                .map(lc -> {
                    CaseDefinition cd = caseById.get(lc.getCaseId());
                    return new CaseSelectionResponse(
                            lc.getCaseId(),
                            cd != null ? cd.getDisplayName() : null,
                            lc.getQuantity(),
                            cd != null ? cd.getPriceVp() : 0L);
                })
                .toList();
    }

    private Map<Integer, List<RolledSkinResponse>> buildRollsByIndex(List<BattleRoll> rolls) {
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
