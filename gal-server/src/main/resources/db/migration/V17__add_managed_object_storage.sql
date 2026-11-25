-- Execute manually after all V17 prechecks are zero. Back up user and the schema.
-- MySQL DDL commits implicitly. Keep STORAGE_ENABLED=false until deployment is ready.
-- File IDs are stable identifiers. Expiring access tokens are generated on demand.
CREATE TABLE storage_file (
 id CHAR(32) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
 owner_id BIGINT NOT NULL,
 purpose VARCHAR(32) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
 bucket VARCHAR(63) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
 object_key VARCHAR(512) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
 content_type VARCHAR(100) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
 size_bytes BIGINT NOT NULL,
 sha256 CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
 status VARCHAR(24) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
 cleanup_attempts INT NOT NULL DEFAULT 0,
 next_attempt_at DATETIME(6) NOT NULL,
 create_time DATETIME(6) NOT NULL,
 update_time DATETIME(6) NOT NULL,
 PRIMARY KEY(id),
 UNIQUE KEY uk_storage_file_owner(id,owner_id),
 UNIQUE KEY uk_storage_object(bucket,object_key),
 KEY idx_storage_cleanup(status,next_attempt_at,id),
 KEY idx_storage_owner(owner_id,purpose,create_time,id),
 CONSTRAINT fk_storage_owner_v17 FOREIGN KEY(owner_id) REFERENCES `user`(id) ON DELETE RESTRICT,
 CONSTRAINT chk_storage_file_v17 CHECK(size_bytes>0 AND cleanup_attempts>=0
  AND status IN ('UPLOADING','READY','CLEANUP_PENDING','DELETED')
  AND id REGEXP '^[0-9a-f]{32}$' AND sha256 REGEXP '^[0-9a-f]{64}$')
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

ALTER TABLE `user`
 ADD COLUMN avatar_file_id CHAR(32) CHARACTER SET ascii COLLATE ascii_bin NULL AFTER avatar_url,
 ADD KEY idx_user_avatar_owner_v17(avatar_file_id,id),
 ADD CONSTRAINT fk_user_avatar_owner_v17 FOREIGN KEY(avatar_file_id,id)
  REFERENCES storage_file(id,owner_id) ON DELETE RESTRICT;
