-- The pillar is no longer damageable and has no hp (its prototype). The stored test game's pillar
-- has hp, and the live one was destroyed once damage at 0 hp became death, so this deletes the test
-- game alone (game …0001 and its one area …0002: everything in the area, its log and its owed
-- turn): at startup libs/world-seed sees no test game and seeds it afresh. The dive game and the
-- arenas are left as they are.
DELETE FROM turn_outbox WHERE area_id = 'area:00000000-0000-4000-8000-000000000002';
DELETE FROM area_log WHERE area_id = 'area:00000000-0000-4000-8000-000000000002';
DELETE FROM area_log_head WHERE area_id = 'area:00000000-0000-4000-8000-000000000002';
-- One statement, so the parent_id foreign key is checked once all of it is gone.
DELETE FROM resources
WHERE id = 'game:00000000-0000-4000-8000-000000000001'
   OR area_id = 'area:00000000-0000-4000-8000-000000000002';
