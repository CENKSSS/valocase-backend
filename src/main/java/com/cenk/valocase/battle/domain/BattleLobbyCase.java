package com.cenk.valocase.battle.domain;

import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * One selected case of a {@link BattleLobby}: a case id and how many times every
 * participant opens it. A lobby has 1..4 of these; total openings per participant
 * is the sum of their quantities (mirrored onto {@link BattleLobby#getRounds()}).
 */
@Entity
@Table(name = "battle_lobby_cases")
@Getter
@Setter
@NoArgsConstructor
public class BattleLobbyCase {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    @Column(name = "lobby_id", nullable = false, updatable = false)
    private UUID lobbyId;

    @Column(name = "ordinal", nullable = false, updatable = false)
    private int ordinal;

    @Column(name = "case_id", length = 100, nullable = false, updatable = false)
    private String caseId;

    @Column(name = "quantity", nullable = false, updatable = false)
    private int quantity;
}
