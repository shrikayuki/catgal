-- Requires V7. Execute manually before enabling external game associations.
-- Existing game data is retained. MySQL DDL commits implicitly.
CREATE TABLE game_external_link (
    game_id BIGINT NOT NULL,
    provider VARCHAR(16) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    external_id VARCHAR(32) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    snapshot_title VARCHAR(512) NOT NULL COMMENT '来源标题快照，绑定时抓取',
    created_by BIGINT NOT NULL COMMENT '首次绑定的管理员',
    create_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    update_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (game_id, provider),
    UNIQUE KEY uk_external_game_identity (provider, external_id),
    CONSTRAINT fk_external_link_game_v8 FOREIGN KEY (game_id) REFERENCES game(id)
        ON DELETE RESTRICT ON UPDATE RESTRICT,
    CONSTRAINT chk_external_provider_v8 CHECK (provider IN ('bangumi', 'vndb')),
    CONSTRAINT chk_external_id_v8 CHECK (CHAR_LENGTH(external_id) BETWEEN 1 AND 32)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='游戏与外部资料站条目的人工确认关联';
