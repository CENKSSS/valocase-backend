package com.cenk.valocase.battle.dto;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.junit.jupiter.api.Test;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * Locks the JSON field names Unity reads for a drawn battle. The client matches
 * {@code isDraw} and {@code refundVp} literally (Unity's JsonUtility does no name
 * mapping), so a rename here silently makes every draw render as a normal win.
 */
class BattleDrawContractTest {

    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void lobbyResponse_exposesDrawFieldsUnderTheExactNamesUnityReads() {
        LobbyResponse response = new LobbyResponse(
                "b-123", "COMPLETED",
                new LobbyCreatorResponse("acc-1", "Cenk", "av_3"),
                "vandal_basic", "Basic Vandal Case", List.of(),
                1, 500L, 2, 2, List.of(),
                null, null, false, null,
                -1, null, null, false,
                false, null,
                true, 500L);

        JsonNode json = mapper.readTree(mapper.writeValueAsString(response));

        assertTrue(json.has("isDraw"), "Unity reads the field as isDraw");
        assertTrue(json.get("isDraw").asBoolean());
        assertEquals(500L, json.get("refundVp").asLong());
        // A draw must report no winner by index and no winner by name.
        assertEquals(-1, json.get("winnerSlotIndex").asInt());
        assertTrue(json.get("winnerDisplayName").isNull());
    }

    @Test
    void battleResultResponse_exposesDrawFieldsUnderTheExactNamesUnityReads() {
        BattleResultResponse response = new BattleResultResponse(
                "bot-77", "vandal_basic", 1, 500L, 10000L,
                -1, false, List.of(), List.of(), true, 500L);

        JsonNode json = mapper.readTree(mapper.writeValueAsString(response));

        assertTrue(json.has("isDraw"), "Unity reads the field as isDraw");
        assertTrue(json.get("isDraw").asBoolean());
        assertEquals(500L, json.get("refundVp").asLong());
        assertEquals(-1, json.get("winnerIndex").asInt());
        assertTrue(json.get("grantedInventoryItemIds").isEmpty());
    }
}
