-- Run db/checks/V7__catalog_preflight.sql first. Back up the schema before applying.
-- Requires V6. MySQL DDL commits implicitly. Stop on the first SQL error.
-- This migration preserves catalog records and existing unique keys.
-- Deploy the updated Java application only after all statements succeed.

ALTER TABLE game
    ADD COLUMN revision BIGINT NOT NULL DEFAULT 0 COMMENT '资料维护修订号',
    ADD CONSTRAINT chk_game_name_v7 CHECK (CHAR_LENGTH(TRIM(name)) > 0),
    ADD CONSTRAINT chk_game_revision_v7 CHECK (revision >= 0);

ALTER TABLE company
    ADD COLUMN revision BIGINT NOT NULL DEFAULT 0 COMMENT '资料维护修订号',
    ADD COLUMN update_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    ADD CONSTRAINT chk_company_name_v7 CHECK (CHAR_LENGTH(TRIM(name)) > 0),
    ADD CONSTRAINT chk_company_revision_v7 CHECK (revision >= 0);

ALTER TABLE tag
    ADD COLUMN revision BIGINT NOT NULL DEFAULT 0 COMMENT '资料维护修订号',
    ADD COLUMN update_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    ADD CONSTRAINT chk_tag_name_v7 CHECK (CHAR_LENGTH(TRIM(name)) > 0),
    ADD CONSTRAINT chk_tag_revision_v7 CHECK (revision >= 0);

ALTER TABLE game_company
    ADD CONSTRAINT fk_game_company_game_v7 FOREIGN KEY (game_id) REFERENCES game(id) ON DELETE RESTRICT ON UPDATE RESTRICT,
    ADD CONSTRAINT fk_game_company_company_v7 FOREIGN KEY (company_id) REFERENCES company(id) ON DELETE RESTRICT ON UPDATE RESTRICT;

ALTER TABLE game_tag
    ADD CONSTRAINT fk_game_tag_game_v7 FOREIGN KEY (game_id) REFERENCES game(id) ON DELETE RESTRICT ON UPDATE RESTRICT,
    ADD CONSTRAINT fk_game_tag_tag_v7 FOREIGN KEY (tag_id) REFERENCES tag(id) ON DELETE RESTRICT ON UPDATE RESTRICT;
