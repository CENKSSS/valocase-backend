-- ValoCase backend - Earn VP 2x ad bonus flag.
--
-- bonus2x_active arms a server-authoritative 2x bonus on the current Earn VP
-- session; the earn claim doubles the reward and clears the flag. Default false so
-- existing sessions remain unbuffed.
--
-- Additive only; column type/nullability matches the JPA entity so
-- ddl-auto=validate passes.

ALTER TABLE earn_vp_sessions
    ADD COLUMN bonus2x_active BOOLEAN NOT NULL DEFAULT false;
