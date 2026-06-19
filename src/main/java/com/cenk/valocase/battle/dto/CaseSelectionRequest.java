package com.cenk.valocase.battle.dto;

/**
 * One selected case in a create-lobby request: the case id and how many times
 * every participant opens it (1..5). Boxed types so omitted values are rejected
 * rather than silently defaulting.
 */
public record CaseSelectionRequest(String caseId, Integer quantity) {
}
