-- 用户手工执行。旧签名凭据没有版本声明，应用按版本 0 验证，保留原有效期。
-- DDL 会隐式提交。应先备份 user 和库结构，再分语句确认成功。
ALTER TABLE `user`
    ADD COLUMN auth_version BIGINT NOT NULL DEFAULT 0 COMMENT '登录撤销版本，改密事务中递增';

CREATE TABLE auth_revoked_token (
    token_digest BINARY(32) NOT NULL COMMENT '已撤销签名凭据的 SHA-256 摘要',
    expires_at DATETIME(6) NOT NULL COMMENT '凭据原到期时间，UTC',
    PRIMARY KEY (token_digest),
    KEY idx_auth_revoked_token_expiry (expires_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='网站登录凭据撤销记录';
