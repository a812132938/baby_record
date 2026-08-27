-- Idempotent: add an optional audit reference for the family member who ended a sleep.
-- Existing completed sleeps intentionally retain NULL because their ending operator is unknown.
SET @sleep_end_add_column_sql = IF(
  (SELECT COUNT(*)
     FROM INFORMATION_SCHEMA.COLUMNS
    WHERE TABLE_SCHEMA = DATABASE()
      AND TABLE_NAME = 'baby_event'
      AND COLUMN_NAME = 'end_operator_id') = 0,
  'ALTER TABLE baby_event ADD COLUMN end_operator_id BIGINT UNSIGNED NULL AFTER operator_id',
  'SELECT 1'
);
PREPARE sleep_end_add_column FROM @sleep_end_add_column_sql;
EXECUTE sleep_end_add_column;
DEALLOCATE PREPARE sleep_end_add_column;

SET @sleep_end_add_index_sql = IF(
  (SELECT COUNT(*)
     FROM INFORMATION_SCHEMA.STATISTICS
    WHERE TABLE_SCHEMA = DATABASE()
      AND TABLE_NAME = 'baby_event'
      AND INDEX_NAME = 'idx_end_operator_time') = 0,
  'ALTER TABLE baby_event ADD INDEX idx_end_operator_time (end_operator_id, end_time DESC)',
  'SELECT 1'
);
PREPARE sleep_end_add_index FROM @sleep_end_add_index_sql;
EXECUTE sleep_end_add_index;
DEALLOCATE PREPARE sleep_end_add_index;

SET @sleep_end_add_fk_sql = IF(
  (SELECT COUNT(*)
     FROM INFORMATION_SCHEMA.TABLE_CONSTRAINTS
    WHERE CONSTRAINT_SCHEMA = DATABASE()
      AND TABLE_NAME = 'baby_event'
      AND CONSTRAINT_NAME = 'fk_baby_event_end_operator'
      AND CONSTRAINT_TYPE = 'FOREIGN KEY') = 0,
  'ALTER TABLE baby_event ADD CONSTRAINT fk_baby_event_end_operator FOREIGN KEY (end_operator_id) REFERENCES app_user(id)',
  'SELECT 1'
);
PREPARE sleep_end_add_fk FROM @sleep_end_add_fk_sql;
EXECUTE sleep_end_add_fk;
DEALLOCATE PREPARE sleep_end_add_fk;
