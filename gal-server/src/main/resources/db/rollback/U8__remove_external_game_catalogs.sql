-- Roll back application features first. Export this table before running.
-- This removes external associations and title snapshots permanently.
DROP TABLE game_external_link;
