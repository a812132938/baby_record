CREATE TABLE IF NOT EXISTS family_creation_recovery (
  recovery_key_hash CHAR(64) NOT NULL,
  device_id CHAR(36) NOT NULL,
  user_id BIGINT UNSIGNED NOT NULL,
  family_id BIGINT UNSIGNED NOT NULL,
  created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  expires_at DATETIME(3) NOT NULL,
  PRIMARY KEY (recovery_key_hash),
  KEY idx_family_creation_recovery_expires (expires_at),
  KEY idx_family_creation_recovery_device (device_id),
  KEY idx_family_creation_recovery_user (user_id),
  KEY idx_family_creation_recovery_family (family_id)
) ENGINE=InnoDB;

SET @family_recovery_add_expiry_sql = IF(
  (SELECT COUNT(*)
     FROM INFORMATION_SCHEMA.COLUMNS
    WHERE TABLE_SCHEMA = DATABASE()
      AND TABLE_NAME = 'family_creation_recovery'
      AND COLUMN_NAME = 'expires_at') = 0,
  'ALTER TABLE family_creation_recovery ADD COLUMN expires_at DATETIME(3) NULL AFTER created_at',
  'SELECT 1'
);
PREPARE family_recovery_add_expiry FROM @family_recovery_add_expiry_sql;
EXECUTE family_recovery_add_expiry;
DEALLOCATE PREPARE family_recovery_add_expiry;

UPDATE family_creation_recovery
   SET expires_at = DATE_ADD(created_at, INTERVAL 30 MINUTE)
 WHERE expires_at IS NULL;

ALTER TABLE family_creation_recovery
  MODIFY expires_at DATETIME(3) NOT NULL;

SET @family_recovery_add_expiry_index_sql = IF(
  (SELECT COUNT(*)
     FROM INFORMATION_SCHEMA.STATISTICS
    WHERE TABLE_SCHEMA = DATABASE()
      AND TABLE_NAME = 'family_creation_recovery'
      AND INDEX_NAME = 'idx_family_creation_recovery_expires') = 0,
  'ALTER TABLE family_creation_recovery ADD INDEX idx_family_creation_recovery_expires (expires_at)',
  'SELECT 1'
);
PREPARE family_recovery_add_expiry_index FROM @family_recovery_add_expiry_index_sql;
EXECUTE family_recovery_add_expiry_index;
DEALLOCATE PREPARE family_recovery_add_expiry_index;
