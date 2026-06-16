-- ============================================================================
-- V14 - Additional Melee cases (basic_melee, protocol_melee, radiant_melee)
-- ============================================================================
-- Adds three Melee cases on top of V13. All drop-pool skins already exist as
-- active Melee skins (imported in V13), so no skins are inserted. Does not touch
-- the Vandal cases or melee_case. Catalog tables only; no player-owned tables.
-- ============================================================================

-- Case definitions (3). Upsert by primary key id.
INSERT INTO case_definitions (id, display_name, price_vp, image_ref, active) VALUES
    ('basic_melee', 'Basic Melee Case', 1050, 'Art/Cases/Basic_Melee_Case', TRUE),
    ('protocol_melee', 'Protocol Melee Case', 3500, 'Art/Cases/Protocol_Melee_Case', TRUE),
    ('radiant_melee', 'Radiant Melee Case', 6250, 'Art/Cases/Radiant_Melee_Case', TRUE)
ON CONFLICT (id) DO UPDATE SET
    display_name = EXCLUDED.display_name,
    price_vp     = EXCLUDED.price_vp,
    image_ref    = EXCLUDED.image_ref,
    active       = EXCLUDED.active;

-- Rebuild each case's drop pool: drop its entries, then insert the current pool.
DELETE FROM case_entries WHERE case_id IN ('basic_melee','protocol_melee','radiant_melee');

-- Drop-pool entries (74).
INSERT INTO case_entries (id, case_id, skin_id, weight) VALUES
    (gen_random_uuid(), 'basic_melee', 'skin_melee_hieroscape_blades', 1),
    (gen_random_uuid(), 'basic_melee', 'skin_melee_keys_to_elysium', 1),
    (gen_random_uuid(), 'basic_melee', 'skin_melee_obsidiana', 1),
    (gen_random_uuid(), 'basic_melee', 'skin_melee_k_tac_blade', 1),
    (gen_random_uuid(), 'basic_melee', 'skin_melee_hu_else', 1),
    (gen_random_uuid(), 'basic_melee', 'skin_melee_montage_axe', 1),
    (gen_random_uuid(), 'basic_melee', 'skin_melee_no_limits_bat', 1),
    (gen_random_uuid(), 'basic_melee', 'skin_melee_mk_vii_liberty_combat', 1),
    (gen_random_uuid(), 'basic_melee', 'skin_melee_jellybeam_sugarslice', 1),
    (gen_random_uuid(), 'basic_melee', 'skin_melee_overlay_dagger', 1),
    (gen_random_uuid(), 'basic_melee', 'skin_melee_venturi', 1),
    (gen_random_uuid(), 'basic_melee', 'skin_melee_blade_of_serket', 1),
    (gen_random_uuid(), 'basic_melee', 'skin_melee_solarex_relic', 1),
    (gen_random_uuid(), 'basic_melee', 'skin_melee_bound', 1),
    (gen_random_uuid(), 'basic_melee', 'skin_melee_comet_sword', 1),
    (gen_random_uuid(), 'basic_melee', 'skin_melee_switchback_ascender', 1),
    (gen_random_uuid(), 'basic_melee', 'skin_melee_kaimana', 1),
    (gen_random_uuid(), 'basic_melee', 'skin_melee_outpost', 1),
    (gen_random_uuid(), 'basic_melee', 'skin_melee_luna_s_descent', 1),
    (gen_random_uuid(), 'basic_melee', 'skin_melee_valorant_go_vol_1', 1),
    (gen_random_uuid(), 'protocol_melee', 'skin_melee_venturi', 1),
    (gen_random_uuid(), 'protocol_melee', 'skin_melee_kuronami_no_yaiba', 1),
    (gen_random_uuid(), 'protocol_melee', 'skin_melee_sovereign_sword', 1),
    (gen_random_uuid(), 'protocol_melee', 'skin_melee_evori_s_spellcaster', 1),
    (gen_random_uuid(), 'protocol_melee', 'skin_melee_bolt', 1),
    (gen_random_uuid(), 'protocol_melee', 'skin_melee_wonderstallion_hammer', 1),
    (gen_random_uuid(), 'protocol_melee', 'skin_melee_torque_coiler', 1),
    (gen_random_uuid(), 'protocol_melee', 'skin_melee_emberclad_hammer', 1),
    (gen_random_uuid(), 'protocol_melee', 'skin_melee_mystbloom_kunai', 1),
    (gen_random_uuid(), 'protocol_melee', 'skin_melee_helix_daggers', 1),
    (gen_random_uuid(), 'protocol_melee', 'skin_melee_prosperity', 1),
    (gen_random_uuid(), 'protocol_melee', 'skin_melee_artisan_foil', 1),
    (gen_random_uuid(), 'protocol_melee', 'skin_melee_altitude_knuckle', 1),
    (gen_random_uuid(), 'protocol_melee', 'skin_melee_blades_of_primordia', 1),
    (gen_random_uuid(), 'protocol_melee', 'skin_melee_sys', 1),
    (gen_random_uuid(), 'protocol_melee', 'skin_melee_rgx_11z_pro_karambit', 1),
    (gen_random_uuid(), 'protocol_melee', 'skin_melee_orion_sword', 1),
    (gen_random_uuid(), 'protocol_melee', 'skin_melee_switchback_ascender', 1),
    (gen_random_uuid(), 'protocol_melee', 'skin_melee_araxys_bio_atomizers', 1),
    (gen_random_uuid(), 'protocol_melee', 'skin_melee_catrina', 1),
    (gen_random_uuid(), 'protocol_melee', 'skin_melee_winterwunderland_candy_cane', 1),
    (gen_random_uuid(), 'protocol_melee', 'skin_melee_guardrail_hammer', 1),
    (gen_random_uuid(), 'protocol_melee', 'skin_melee_heart_splitter', 1),
    (gen_random_uuid(), 'protocol_melee', 'skin_melee_forsaken_ritual_blade', 1),
    (gen_random_uuid(), 'protocol_melee', 'skin_melee_rogue_push_daggers', 1),
    (gen_random_uuid(), 'protocol_melee', 'skin_melee_elderflame_dagger', 1),
    (gen_random_uuid(), 'protocol_melee', 'skin_melee_oni_claw', 1),
    (gen_random_uuid(), 'protocol_melee', 'skin_melee_montage_axe', 1),
    (gen_random_uuid(), 'radiant_melee', 'skin_melee_champions_2025_butterfly', 1),
    (gen_random_uuid(), 'radiant_melee', 'skin_melee_glitchpop_axe', 1),
    (gen_random_uuid(), 'radiant_melee', 'skin_melee_doombringer_battleaxe', 1),
    (gen_random_uuid(), 'radiant_melee', 'skin_melee_broken_blade_of_the_ruined_king', 1),
    (gen_random_uuid(), 'radiant_melee', 'skin_melee_ignite_fan', 1),
    (gen_random_uuid(), 'radiant_melee', 'skin_melee_singularity_butterfly', 1),
    (gen_random_uuid(), 'radiant_melee', 'skin_melee_relic_stone_daggers', 1),
    (gen_random_uuid(), 'radiant_melee', 'skin_melee_personal_administrative_melee_unit', 1),
    (gen_random_uuid(), 'radiant_melee', 'skin_melee_terminus_a_quo', 1),
    (gen_random_uuid(), 'radiant_melee', 'skin_melee_elderflame_dagger', 1),
    (gen_random_uuid(), 'radiant_melee', 'skin_melee_champions_2024_blade', 1),
    (gen_random_uuid(), 'radiant_melee', 'skin_melee_champions_2022_butterfly', 1),
    (gen_random_uuid(), 'radiant_melee', 'skin_melee_magepunk_sparkswitch', 1),
    (gen_random_uuid(), 'radiant_melee', 'skin_melee_overdrive_blade', 1),
    (gen_random_uuid(), 'radiant_melee', 'skin_melee_ion_karambit', 1),
    (gen_random_uuid(), 'radiant_melee', 'skin_melee_rgx_11z_pro_karambit', 1),
    (gen_random_uuid(), 'radiant_melee', 'skin_melee_rgx_11z_pro_blade', 1),
    (gen_random_uuid(), 'radiant_melee', 'skin_melee_arcane_gauntlets', 1),
    (gen_random_uuid(), 'radiant_melee', 'skin_melee_glitchpop_dagger', 1),
    (gen_random_uuid(), 'radiant_melee', 'skin_melee_blackthorn_blades', 1),
    (gen_random_uuid(), 'radiant_melee', 'skin_melee_phaseguard_splitter', 1),
    (gen_random_uuid(), 'radiant_melee', 'skin_melee_araxys_bio_atomizers', 1),
    (gen_random_uuid(), 'radiant_melee', 'skin_melee_blades_of_primordia', 1),
    (gen_random_uuid(), 'radiant_melee', 'skin_melee_mystbloom_fanblade', 1),
    (gen_random_uuid(), 'radiant_melee', 'skin_melee_forsaken_ritual_blade', 1),
    (gen_random_uuid(), 'radiant_melee', 'skin_melee_xenohunter', 1);
