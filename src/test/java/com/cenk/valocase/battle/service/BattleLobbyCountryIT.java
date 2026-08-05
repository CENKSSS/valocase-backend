package com.cenk.valocase.battle.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.sql.Timestamp;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

import com.cenk.valocase.account.domain.Account;
import com.cenk.valocase.account.domain.AccountStatus;
import com.cenk.valocase.account.repository.AccountRepository;
import com.cenk.valocase.battle.dto.CaseSelectionRequest;
import com.cenk.valocase.battle.dto.LobbyResponse;
import com.cenk.valocase.battle.dto.LobbySlotResponse;
import com.cenk.valocase.wallet.service.WalletService;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * The lobby response's {@code countryCode} against a real Flyway-migrated
 * Postgres: a real player's slot carries their account's country, while bots,
 * empty slots and accounts that never picked one all read null — the client
 * draws no label for null, so no invented default may ever appear here.
 *
 * <p>No Testcontainers — this project has no Docker. Point the usual Spring
 * datasource properties at any Postgres and run it.
 */
@SpringBootTest
class BattleLobbyCountryIT {

    private static final String CASE_ID = "classic_countryit_0";
    private static final long STARTING_VP = 10_000L;

    @Autowired JdbcTemplate jdbc;
    @Autowired AccountRepository accountRepository;
    @Autowired WalletService walletService;
    @Autowired BattleLobbyService lobbyService;

    private final ObjectMapper mapper = new ObjectMapper();

    /** One cheap "classic_*" case so category unlock never gates these tests. */
    @BeforeEach
    void seedCatalog() {
        jdbc.update("""
                INSERT INTO skins (id, display_name, weapon, rarity, vp_value, active)
                VALUES ('countryit_skin', 'Country IT', 'Classic', 'Select', 250, TRUE)
                ON CONFLICT (id) DO NOTHING
                """);
        jdbc.update("""
                INSERT INTO case_definitions (id, display_name, price_vp, active)
                VALUES (?, 'Country IT', 100, TRUE) ON CONFLICT (id) DO NOTHING
                """, CASE_ID);
        jdbc.update("""
                INSERT INTO case_entries (id, case_id, skin_id, weight)
                VALUES (gen_random_uuid(), ?, 'countryit_skin', 1)
                ON CONFLICT ON CONSTRAINT uq_case_entries_case_skin DO NOTHING
                """, CASE_ID);
    }

    private UUID newPlayer(String name, String countryCode) {
        Account account = new Account();
        account.setGuestToken(UUID.randomUUID());
        account.setDisplayName(name);
        account.setCountryCode(countryCode);
        account.setStatus(AccountStatus.ACTIVE);
        account.setCreatedAt(Instant.now());
        account.setLastSeenAt(Instant.now());
        account.setTotalXp(2000L);
        account = accountRepository.saveAndFlush(account);
        walletService.createInitialWallet(account.getId(), STARTING_VP);
        return account.getId();
    }

    private static LobbySlotResponse slot(LobbyResponse res, int index) {
        return res.slots().stream()
                .filter(s -> s.slotIndex() == index)
                .findFirst()
                .orElseThrow();
    }

    @Test
    void countryCode_followsTheOccupant_andIsNullForBotsAndEmpties() {
        UUID host = newPlayer("Country_Host", "TR");
        UUID german = newPlayer("Country_DE", "DE");
        UUID unset = newPlayer("Country_None", null);

        LobbyResponse created = lobbyService.createLobby(
                host, List.of(new CaseSelectionRequest(CASE_ID, 1)), 3);
        UUID lobbyId = UUID.fromString(created.battleId());
        assertEquals("TR", created.creator().countryCode());
        assertEquals("TR", slot(created, 0).countryCode());
        assertNull(slot(created, 1).countryCode(), "empty slot has no country");
        assertNull(slot(created, 2).countryCode(), "empty slot has no country");

        LobbyResponse joined = lobbyService.joinLobby(german, lobbyId, 1);
        assertEquals("DE", slot(joined, 1).countryCode());

        // Leaving must clear the denormalized country with the other occupant
        // fields, or the next occupant of this slot would inherit it.
        LobbyResponse left = lobbyService.leaveLobby(german, lobbyId);
        assertEquals("EMPTY", slot(left, 1).type());
        assertNull(slot(left, 1).countryCode());

        // An account that never picked a country joins without error and reads null.
        LobbyResponse rejoined = lobbyService.joinLobby(unset, lobbyId, 1);
        assertEquals("REAL", slot(rejoined, 1).type());
        assertNull(slot(rejoined, 1).countryCode());

        // Backdate past the shared Add Bot delay so the host can fill slot 2.
        // Straight SQL because the entity's created_at is not updatable via JPA.
        jdbc.update("UPDATE battle_lobbies SET created_at = ? WHERE id = ?",
                Timestamp.from(Instant.now().minus(30, ChronoUnit.SECONDS)), lobbyId);
        LobbyResponse full = lobbyService.addBot(host, lobbyId);
        assertEquals("BOT", slot(full, 2).type());
        assertNull(slot(full, 2).countryCode());

        // Lock the JSON field name Unity reads (JsonUtility does no name mapping):
        // a real player's slot serializes "countryCode":"TR", everyone else null.
        JsonNode json = mapper.readTree(mapper.writeValueAsString(full));
        assertEquals("TR", json.get("creator").get("countryCode").asString());
        assertEquals("TR", json.get("slots").get(0).get("countryCode").asString());
        assertTrue(json.get("slots").get(1).get("countryCode").isNull());
        assertTrue(json.get("slots").get(2).get("countryCode").isNull());
    }

    @Test
    void hostWithoutCountry_createsLobby_andEveryCountryFieldIsNull() {
        UUID host = newPlayer("Country_OldAccount", null);

        LobbyResponse created = lobbyService.createLobby(
                host, List.of(new CaseSelectionRequest(CASE_ID, 1)), 2);

        assertNull(created.creator().countryCode());
        assertNull(slot(created, 0).countryCode());
        assertNull(slot(created, 1).countryCode());
    }
}
