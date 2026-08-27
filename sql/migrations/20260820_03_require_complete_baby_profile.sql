-- Run only after every existing baby has been completed with real profile data.
DELIMITER //

DROP PROCEDURE IF EXISTS validate_complete_baby_profiles//
CREATE PROCEDURE validate_complete_baby_profiles()
BEGIN
  IF EXISTS (
    SELECT 1
      FROM baby
     WHERE birthday IS NULL
        OR birthday > CURRENT_DATE()
        OR gender IS NULL
        OR gender NOT IN ('BOY', 'GIRL')
        OR birth_weight_grams IS NULL
        OR birth_weight_grams < 100
        OR birth_weight_grams > 15000
     LIMIT 1
  ) THEN
    SIGNAL SQLSTATE '45000'
      SET MESSAGE_TEXT = 'baby profile invalid; collect real birthday, gender and birth weight before constraints';
  END IF;
END//

CALL validate_complete_baby_profiles()//
DROP PROCEDURE validate_complete_baby_profiles//

DELIMITER ;

ALTER TABLE baby
  MODIFY birthday DATE NOT NULL,
  MODIFY gender VARCHAR(16) NOT NULL,
  MODIFY birth_weight_grams SMALLINT UNSIGNED NOT NULL;
