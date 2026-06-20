-- Four additional daily-repeatable missions. They reset on the same 24h
-- next_reset_at cooldown as the existing missions (period ONE_TIME).

INSERT INTO mission_definitions (id, code, title, description, event_type, target_count, reward_vp, period, active, sort_order, created_at) VALUES
    (gen_random_uuid(), 'PLAY_2_BATTLES',  'Play 2 Battles',        'Participate in 2 completed battles.',        'BATTLE_PLAYED',    2,    750,  'ONE_TIME', TRUE, 6, now()),
    (gen_random_uuid(), 'EARN_3000_VP',    'Earn 3000 VP',          'Earn 3000 VP from Tap / Earn VP.',           'VP_EARNED',        3000, 600,  'ONE_TIME', TRUE, 7, now()),
    (gen_random_uuid(), 'SELL_3000_VP',    'Sell Skins Worth 3000', 'Sell skins worth 3000 VP in total.',         'SKIN_SOLD_VALUE',  3000, 700,  'ONE_TIME', TRUE, 8, now()),
    (gen_random_uuid(), 'WIN_2_UPGRADES',  'Win 2 Upgrades',        'Successfully upgrade 2 skins.',              'UPGRADE_SUCCEEDED', 2,   1200, 'ONE_TIME', TRUE, 9, now());
