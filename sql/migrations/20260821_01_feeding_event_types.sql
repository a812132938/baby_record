-- Idempotent: this migration only replaces validation triggers and never rewrites existing events.
DELIMITER //

DROP TRIGGER IF EXISTS baby_event_amount_before_insert//
CREATE TRIGGER baby_event_amount_before_insert
BEFORE INSERT ON baby_event
FOR EACH ROW
BEGIN
  IF NEW.amount_ml IS NOT NULL AND (NEW.amount_ml < 1 OR NEW.amount_ml > 1000) THEN
    SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 'amount_ml must be between 1 and 1000';
  END IF;
  IF NEW.event_type IN ('FEED', 'BOTTLE_BREAST_MILK', 'FORMULA_FEED') AND NEW.amount_ml IS NULL THEN
    SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 'bottle feeding amount_ml is required';
  END IF;
  IF NEW.event_type = 'DIRECT_BREASTFEED' AND (
       NEW.amount_ml IS NOT NULL OR NEW.event_data IS NULL
       OR JSON_EXTRACT(NEW.event_data, '$.schemaVersion') IS NULL
       OR JSON_TYPE(JSON_EXTRACT(NEW.event_data, '$.schemaVersion')) <> 'INTEGER'
       OR CAST(JSON_UNQUOTE(JSON_EXTRACT(NEW.event_data, '$.schemaVersion')) AS SIGNED) <> 1
       OR JSON_EXTRACT(NEW.event_data, '$.leftSeconds') IS NULL
       OR JSON_EXTRACT(NEW.event_data, '$.rightSeconds') IS NULL
       OR JSON_TYPE(JSON_EXTRACT(NEW.event_data, '$.leftSeconds')) <> 'INTEGER'
       OR JSON_TYPE(JSON_EXTRACT(NEW.event_data, '$.rightSeconds')) <> 'INTEGER'
       OR CAST(JSON_UNQUOTE(JSON_EXTRACT(NEW.event_data, '$.leftSeconds')) AS SIGNED) < 0
       OR CAST(JSON_UNQUOTE(JSON_EXTRACT(NEW.event_data, '$.rightSeconds')) AS SIGNED) < 0
       OR CAST(JSON_UNQUOTE(JSON_EXTRACT(NEW.event_data, '$.leftSeconds')) AS SIGNED)
          + CAST(JSON_UNQUOTE(JSON_EXTRACT(NEW.event_data, '$.rightSeconds')) AS SIGNED) NOT BETWEEN 1 AND 86400
       OR JSON_EXTRACT(NEW.event_data, '$.lastSide') IS NULL
       OR JSON_TYPE(JSON_EXTRACT(NEW.event_data, '$.lastSide')) <> 'STRING'
       OR JSON_UNQUOTE(JSON_EXTRACT(NEW.event_data, '$.lastSide')) NOT IN ('LEFT', 'RIGHT')
       OR (JSON_UNQUOTE(JSON_EXTRACT(NEW.event_data, '$.lastSide')) = 'LEFT'
           AND CAST(JSON_UNQUOTE(JSON_EXTRACT(NEW.event_data, '$.leftSeconds')) AS SIGNED) = 0)
       OR (JSON_UNQUOTE(JSON_EXTRACT(NEW.event_data, '$.lastSide')) = 'RIGHT'
           AND CAST(JSON_UNQUOTE(JSON_EXTRACT(NEW.event_data, '$.rightSeconds')) AS SIGNED) = 0)
     ) THEN
    SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 'invalid direct breastfeeding data';
  END IF;
  IF NEW.event_type = 'PUMPING' AND (
       NEW.amount_ml IS NULL OR NEW.event_data IS NULL
       OR JSON_EXTRACT(NEW.event_data, '$.schemaVersion') IS NULL
       OR JSON_TYPE(JSON_EXTRACT(NEW.event_data, '$.schemaVersion')) <> 'INTEGER'
       OR CAST(JSON_UNQUOTE(JSON_EXTRACT(NEW.event_data, '$.schemaVersion')) AS SIGNED) <> 1
       OR JSON_EXTRACT(NEW.event_data, '$.leftMl') IS NULL
       OR JSON_EXTRACT(NEW.event_data, '$.rightMl') IS NULL
       OR JSON_TYPE(JSON_EXTRACT(NEW.event_data, '$.leftMl')) <> 'INTEGER'
       OR JSON_TYPE(JSON_EXTRACT(NEW.event_data, '$.rightMl')) <> 'INTEGER'
       OR CAST(JSON_UNQUOTE(JSON_EXTRACT(NEW.event_data, '$.leftMl')) AS SIGNED) < 0
       OR CAST(JSON_UNQUOTE(JSON_EXTRACT(NEW.event_data, '$.rightMl')) AS SIGNED) < 0
       OR CAST(JSON_UNQUOTE(JSON_EXTRACT(NEW.event_data, '$.leftMl')) AS SIGNED)
          + CAST(JSON_UNQUOTE(JSON_EXTRACT(NEW.event_data, '$.rightMl')) AS SIGNED) <> NEW.amount_ml
       OR (JSON_EXTRACT(NEW.event_data, '$.durationSeconds') IS NOT NULL
           AND JSON_TYPE(JSON_EXTRACT(NEW.event_data, '$.durationSeconds')) NOT IN ('NULL', 'INTEGER'))
       OR (JSON_EXTRACT(NEW.event_data, '$.durationSeconds') IS NOT NULL
           AND JSON_UNQUOTE(JSON_EXTRACT(NEW.event_data, '$.durationSeconds')) <> 'null'
           AND CAST(JSON_UNQUOTE(JSON_EXTRACT(NEW.event_data, '$.durationSeconds')) AS SIGNED) NOT BETWEEN 1 AND 86400)
     ) THEN
    SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 'invalid pumping data';
  END IF;
END//

DROP TRIGGER IF EXISTS baby_event_amount_before_update//
CREATE TRIGGER baby_event_amount_before_update
BEFORE UPDATE ON baby_event
FOR EACH ROW
BEGIN
  IF NEW.amount_ml IS NOT NULL AND (NEW.amount_ml < 1 OR NEW.amount_ml > 1000) THEN
    SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 'amount_ml must be between 1 and 1000';
  END IF;
  IF NEW.event_type IN ('FEED', 'BOTTLE_BREAST_MILK', 'FORMULA_FEED') AND NEW.amount_ml IS NULL THEN
    SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 'bottle feeding amount_ml is required';
  END IF;
  IF NEW.event_type = 'DIRECT_BREASTFEED' AND (
       NEW.amount_ml IS NOT NULL OR NEW.event_data IS NULL
       OR JSON_EXTRACT(NEW.event_data, '$.schemaVersion') IS NULL
       OR JSON_TYPE(JSON_EXTRACT(NEW.event_data, '$.schemaVersion')) <> 'INTEGER'
       OR CAST(JSON_UNQUOTE(JSON_EXTRACT(NEW.event_data, '$.schemaVersion')) AS SIGNED) <> 1
       OR JSON_EXTRACT(NEW.event_data, '$.leftSeconds') IS NULL
       OR JSON_EXTRACT(NEW.event_data, '$.rightSeconds') IS NULL
       OR JSON_TYPE(JSON_EXTRACT(NEW.event_data, '$.leftSeconds')) <> 'INTEGER'
       OR JSON_TYPE(JSON_EXTRACT(NEW.event_data, '$.rightSeconds')) <> 'INTEGER'
       OR CAST(JSON_UNQUOTE(JSON_EXTRACT(NEW.event_data, '$.leftSeconds')) AS SIGNED) < 0
       OR CAST(JSON_UNQUOTE(JSON_EXTRACT(NEW.event_data, '$.rightSeconds')) AS SIGNED) < 0
       OR CAST(JSON_UNQUOTE(JSON_EXTRACT(NEW.event_data, '$.leftSeconds')) AS SIGNED)
          + CAST(JSON_UNQUOTE(JSON_EXTRACT(NEW.event_data, '$.rightSeconds')) AS SIGNED) NOT BETWEEN 1 AND 86400
       OR JSON_EXTRACT(NEW.event_data, '$.lastSide') IS NULL
       OR JSON_TYPE(JSON_EXTRACT(NEW.event_data, '$.lastSide')) <> 'STRING'
       OR JSON_UNQUOTE(JSON_EXTRACT(NEW.event_data, '$.lastSide')) NOT IN ('LEFT', 'RIGHT')
       OR (JSON_UNQUOTE(JSON_EXTRACT(NEW.event_data, '$.lastSide')) = 'LEFT'
           AND CAST(JSON_UNQUOTE(JSON_EXTRACT(NEW.event_data, '$.leftSeconds')) AS SIGNED) = 0)
       OR (JSON_UNQUOTE(JSON_EXTRACT(NEW.event_data, '$.lastSide')) = 'RIGHT'
           AND CAST(JSON_UNQUOTE(JSON_EXTRACT(NEW.event_data, '$.rightSeconds')) AS SIGNED) = 0)
     ) THEN
    SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 'invalid direct breastfeeding data';
  END IF;
  IF NEW.event_type = 'PUMPING' AND (
       NEW.amount_ml IS NULL OR NEW.event_data IS NULL
       OR JSON_EXTRACT(NEW.event_data, '$.schemaVersion') IS NULL
       OR JSON_TYPE(JSON_EXTRACT(NEW.event_data, '$.schemaVersion')) <> 'INTEGER'
       OR CAST(JSON_UNQUOTE(JSON_EXTRACT(NEW.event_data, '$.schemaVersion')) AS SIGNED) <> 1
       OR JSON_EXTRACT(NEW.event_data, '$.leftMl') IS NULL
       OR JSON_EXTRACT(NEW.event_data, '$.rightMl') IS NULL
       OR JSON_TYPE(JSON_EXTRACT(NEW.event_data, '$.leftMl')) <> 'INTEGER'
       OR JSON_TYPE(JSON_EXTRACT(NEW.event_data, '$.rightMl')) <> 'INTEGER'
       OR CAST(JSON_UNQUOTE(JSON_EXTRACT(NEW.event_data, '$.leftMl')) AS SIGNED) < 0
       OR CAST(JSON_UNQUOTE(JSON_EXTRACT(NEW.event_data, '$.rightMl')) AS SIGNED) < 0
       OR CAST(JSON_UNQUOTE(JSON_EXTRACT(NEW.event_data, '$.leftMl')) AS SIGNED)
          + CAST(JSON_UNQUOTE(JSON_EXTRACT(NEW.event_data, '$.rightMl')) AS SIGNED) <> NEW.amount_ml
       OR (JSON_EXTRACT(NEW.event_data, '$.durationSeconds') IS NOT NULL
           AND JSON_TYPE(JSON_EXTRACT(NEW.event_data, '$.durationSeconds')) NOT IN ('NULL', 'INTEGER'))
       OR (JSON_EXTRACT(NEW.event_data, '$.durationSeconds') IS NOT NULL
           AND JSON_UNQUOTE(JSON_EXTRACT(NEW.event_data, '$.durationSeconds')) <> 'null'
           AND CAST(JSON_UNQUOTE(JSON_EXTRACT(NEW.event_data, '$.durationSeconds')) AS SIGNED) NOT BETWEEN 1 AND 86400)
     ) THEN
    SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 'invalid pumping data';
  END IF;
END//

DELIMITER ;
