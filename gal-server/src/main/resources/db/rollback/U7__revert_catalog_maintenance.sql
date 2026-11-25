-- Stop the V7 application and restore the previous application before running this rollback.
-- New revision values and company/tag modification timestamps will be lost.
ALTER TABLE game_tag DROP FOREIGN KEY fk_game_tag_game_v7, DROP FOREIGN KEY fk_game_tag_tag_v7;
ALTER TABLE game_company DROP FOREIGN KEY fk_game_company_game_v7, DROP FOREIGN KEY fk_game_company_company_v7;
ALTER TABLE tag DROP CHECK chk_tag_name_v7, DROP CHECK chk_tag_revision_v7, DROP COLUMN revision, DROP COLUMN update_time;
ALTER TABLE company DROP CHECK chk_company_name_v7, DROP CHECK chk_company_revision_v7, DROP COLUMN revision, DROP COLUMN update_time;
ALTER TABLE game DROP CHECK chk_game_name_v7, DROP CHECK chk_game_revision_v7, DROP COLUMN revision;
