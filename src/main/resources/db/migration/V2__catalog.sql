-- ValoCase backend - Phase 1 catalog schema.
--
-- Tables: skins, case_definitions, case_entries.
-- IDs for skins and cases are the Unity stable IDs, stored verbatim as the
-- primary keys (VARCHAR). case_entries uses a generated UUID.
--
-- Column types/nullability deliberately match the JPA entity mappings so that
-- Hibernate's ddl-auto=validate passes against this Flyway-owned schema.

CREATE TABLE skins (
    id           VARCHAR(100) PRIMARY KEY,
    display_name VARCHAR(150) NOT NULL,
    weapon       VARCHAR(100) NOT NULL,
    rarity       VARCHAR(50)  NOT NULL,
    vp_value     INTEGER      NOT NULL,
    image_ref    VARCHAR(255),
    active       BOOLEAN      NOT NULL DEFAULT TRUE
);

CREATE TABLE case_definitions (
    id           VARCHAR(100) PRIMARY KEY,
    display_name VARCHAR(150) NOT NULL,
    price_vp     INTEGER      NOT NULL,
    image_ref    VARCHAR(255),
    active       BOOLEAN      NOT NULL DEFAULT TRUE
);

CREATE TABLE case_entries (
    id      UUID         PRIMARY KEY,
    case_id VARCHAR(100) NOT NULL REFERENCES case_definitions (id),
    skin_id VARCHAR(100) NOT NULL REFERENCES skins (id),
    weight  INTEGER      NOT NULL,
    CONSTRAINT uq_case_entries_case_skin UNIQUE (case_id, skin_id)
);

CREATE INDEX idx_case_entries_case_id ON case_entries (case_id);
