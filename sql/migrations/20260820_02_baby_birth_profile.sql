-- Existing rows remain unknown until a family member supplies the real values.
SET @baby_profile_add_gender_sql = IF(
  (SELECT COUNT(*)
     FROM INFORMATION_SCHEMA.COLUMNS
    WHERE TABLE_SCHEMA = DATABASE()
      AND TABLE_NAME = 'baby'
      AND COLUMN_NAME = 'gender') = 0,
  'ALTER TABLE baby ADD COLUMN gender VARCHAR(16) NULL AFTER birthday',
  'SELECT 1'
);
PREPARE baby_profile_add_gender FROM @baby_profile_add_gender_sql;
EXECUTE baby_profile_add_gender;
DEALLOCATE PREPARE baby_profile_add_gender;

SET @baby_profile_add_weight_sql = IF(
  (SELECT COUNT(*)
     FROM INFORMATION_SCHEMA.COLUMNS
    WHERE TABLE_SCHEMA = DATABASE()
      AND TABLE_NAME = 'baby'
      AND COLUMN_NAME = 'birth_weight_grams') = 0,
  'ALTER TABLE baby ADD COLUMN birth_weight_grams SMALLINT UNSIGNED NULL AFTER gender',
  'SELECT 1'
);
PREPARE baby_profile_add_weight FROM @baby_profile_add_weight_sql;
EXECUTE baby_profile_add_weight;
DEALLOCATE PREPARE baby_profile_add_weight;

DELIMITER //

DROP TRIGGER IF EXISTS baby_profile_before_insert//
CREATE TRIGGER baby_profile_before_insert
BEFORE INSERT ON baby
FOR EACH ROW
BEGIN
  IF NEW.birthday IS NULL
     OR NEW.birthday > CURRENT_DATE()
     OR NEW.gender IS NULL
     OR NEW.gender NOT IN ('BOY', 'GIRL')
     OR NEW.birth_weight_grams IS NULL
     OR NEW.birth_weight_grams < 100
     OR NEW.birth_weight_grams > 15000 THEN
    SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 'baby profile requires non-future birthday, BOY/GIRL gender, and birth weight 100..15000g';
  END IF;
END//

DROP TRIGGER IF EXISTS baby_profile_before_update//
CREATE TRIGGER baby_profile_before_update
BEFORE UPDATE ON baby
FOR EACH ROW
BEGIN
  IF NEW.birthday IS NULL
     OR NEW.birthday > CURRENT_DATE()
     OR NEW.gender IS NULL
     OR NEW.gender NOT IN ('BOY', 'GIRL')
     OR NEW.birth_weight_grams IS NULL
     OR NEW.birth_weight_grams < 100
     OR NEW.birth_weight_grams > 15000 THEN
    SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 'baby profile requires non-future birthday, BOY/GIRL gender, and birth weight 100..15000g';
  END IF;
END//

DELIMITER ;
