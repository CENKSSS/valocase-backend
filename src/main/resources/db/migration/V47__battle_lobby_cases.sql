-- ValoCase backend - multi-case PvP lobbies.
--
-- A lobby may select 1..4 cases, each with a 1..5 quantity (openings per
-- participant). The legacy battle_lobbies.case_id / rounds columns are kept:
-- case_id holds the first selected case and rounds holds the total openings
-- (sum of quantities), so the immutable battle result keeps working unchanged.
--
-- Column types/nullability match the JPA entity so ddl-auto=validate passes.

CREATE TABLE battle_lobby_cases (
    id        UUID PRIMARY KEY,
    lobby_id  UUID         NOT NULL REFERENCES battle_lobbies (id),
    ordinal   INTEGER      NOT NULL,
    case_id   VARCHAR(100) NOT NULL REFERENCES case_definitions (id),
    quantity  INTEGER      NOT NULL,
    CONSTRAINT uq_battle_lobby_cases_case UNIQUE (lobby_id, case_id)
);

CREATE INDEX idx_battle_lobby_cases_lobby ON battle_lobby_cases (lobby_id);
