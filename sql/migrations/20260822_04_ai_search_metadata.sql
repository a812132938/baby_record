SET @ai_search_used_exists = (
  SELECT COUNT(*)
    FROM information_schema.COLUMNS
   WHERE TABLE_SCHEMA = DATABASE()
     AND TABLE_NAME = 'ai_message'
     AND COLUMN_NAME = 'search_used'
);

SET @ai_search_used_ddl = IF(
  @ai_search_used_exists = 0,
  'ALTER TABLE ai_message ADD COLUMN search_used TINYINT(1) NOT NULL DEFAULT 0 AFTER error_code',
  'SELECT 1'
);

PREPARE ai_search_used_statement FROM @ai_search_used_ddl;
EXECUTE ai_search_used_statement;
DEALLOCATE PREPARE ai_search_used_statement;
