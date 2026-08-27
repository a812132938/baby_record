CREATE TABLE IF NOT EXISTS app_user (
  id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  nickname VARCHAR(64) NOT NULL,
  created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  PRIMARY KEY (id)
) ENGINE=InnoDB;

CREATE TABLE IF NOT EXISTS family (
  id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  name VARCHAR(64) NOT NULL,
  invite_code VARCHAR(32) NOT NULL,
  created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  PRIMARY KEY (id),
  UNIQUE KEY uk_family_invite_code (invite_code)
) ENGINE=InnoDB;

CREATE TABLE IF NOT EXISTS family_member (
  family_id BIGINT UNSIGNED NOT NULL,
  user_id BIGINT UNSIGNED NOT NULL,
  role VARCHAR(24) NOT NULL DEFAULT 'MEMBER',
  created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  PRIMARY KEY (family_id, user_id),
  KEY idx_family_member_user (user_id)
) ENGINE=InnoDB;

CREATE TABLE IF NOT EXISTS baby (
  id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  family_id BIGINT UNSIGNED NOT NULL,
  nickname VARCHAR(64) NOT NULL,
  birthday DATE NOT NULL,
  gender VARCHAR(16) NOT NULL,
  birth_weight_grams SMALLINT UNSIGNED NOT NULL,
  created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  updated_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
  PRIMARY KEY (id),
  KEY idx_baby_family (family_id)
) ENGINE=InnoDB;

CREATE TABLE IF NOT EXISTS trusted_device (
  id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  user_id BIGINT UNSIGNED NOT NULL,
  family_id BIGINT UNSIGNED NOT NULL,
  device_id CHAR(36) NOT NULL,
  device_name VARCHAR(120) NULL,
  refresh_token_hash CHAR(64) NOT NULL,
  last_active_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  expires_at DATETIME(3) NULL,
  revoked_at DATETIME(3) NULL,
  created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  PRIMARY KEY (id),
  UNIQUE KEY uk_trusted_device_id (device_id),
  UNIQUE KEY uk_trusted_device_token (refresh_token_hash),
  KEY idx_trusted_device_user (user_id, revoked_at),
  KEY idx_trusted_device_family (family_id, revoked_at)
) ENGINE=InnoDB;

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

CREATE TABLE IF NOT EXISTS baby_event (
  id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  baby_id BIGINT UNSIGNED NOT NULL,
  operator_id BIGINT UNSIGNED NOT NULL,
  end_operator_id BIGINT UNSIGNED NULL,
  client_event_id CHAR(36) NULL,
  event_type VARCHAR(24) NOT NULL COMMENT 'FEED/DIRECT_BREASTFEED/BOTTLE_BREAST_MILK/FORMULA_FEED/PUMPING/SLEEP/POOP/PEE',
  start_time DATETIME(3) NOT NULL,
  end_time DATETIME(3) NULL,
  amount_ml SMALLINT UNSIGNED NULL,
  event_data JSON NULL,
  created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  updated_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
  PRIMARY KEY (id),
  UNIQUE KEY uk_baby_client_event (baby_id, client_event_id),
  KEY idx_baby_type_time (baby_id, event_type, start_time DESC),
  KEY idx_baby_time (baby_id, start_time DESC),
  KEY idx_operator_time (operator_id, start_time DESC),
  KEY idx_end_operator_time (end_operator_id, end_time DESC),
  CONSTRAINT fk_baby_event_end_operator FOREIGN KEY (end_operator_id) REFERENCES app_user(id),
  CONSTRAINT chk_feed_amount CHECK (amount_ml IS NULL OR amount_ml BETWEEN 1 AND 1000)
) ENGINE=InnoDB;

CREATE TABLE IF NOT EXISTS ai_conversation (
  id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  family_id BIGINT UNSIGNED NOT NULL,
  baby_id BIGINT UNSIGNED NOT NULL,
  created_by BIGINT UNSIGNED NOT NULL,
  client_request_id CHAR(36) NOT NULL,
  title VARCHAR(120) NOT NULL,
  status VARCHAR(24) NOT NULL COMMENT 'ANALYZING/RESPONDING/READY/FAILED',
  model VARCHAR(80) NOT NULL,
  last_error_code VARCHAR(48) NULL,
  data_processing_accepted_at DATETIME(3) NOT NULL,
  archived_at DATETIME(3) NULL,
  created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  updated_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
  PRIMARY KEY (id),
  UNIQUE KEY uk_ai_conversation_request (family_id, baby_id, client_request_id),
  KEY idx_ai_conversation_scope (family_id, baby_id, archived_at, updated_at),
  KEY idx_ai_conversation_creator (created_by, created_at),
  CONSTRAINT fk_ai_conversation_family FOREIGN KEY (family_id) REFERENCES family(id),
  CONSTRAINT fk_ai_conversation_baby FOREIGN KEY (baby_id) REFERENCES baby(id),
  CONSTRAINT fk_ai_conversation_creator FOREIGN KEY (created_by) REFERENCES app_user(id)
) ENGINE=InnoDB;

CREATE TABLE IF NOT EXISTS ai_snapshot (
  id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  conversation_id BIGINT UNSIGNED NOT NULL,
  family_id BIGINT UNSIGNED NOT NULL,
  baby_id BIGINT UNSIGNED NOT NULL,
  snapshot_at DATETIME(3) NOT NULL,
  range_start DATETIME(3) NULL,
  range_end DATETIME(3) NOT NULL,
  source_event_count INT UNSIGNED NOT NULL,
  prompt_version VARCHAR(48) NOT NULL,
  dashboard JSON NOT NULL,
  prompt_text MEDIUMTEXT NOT NULL,
  created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  PRIMARY KEY (id),
  KEY idx_ai_snapshot_scope (family_id, baby_id, conversation_id, snapshot_at),
  KEY idx_ai_snapshot_conversation (conversation_id, id),
  CONSTRAINT fk_ai_snapshot_conversation FOREIGN KEY (conversation_id) REFERENCES ai_conversation(id),
  CONSTRAINT fk_ai_snapshot_family FOREIGN KEY (family_id) REFERENCES family(id),
  CONSTRAINT fk_ai_snapshot_baby FOREIGN KEY (baby_id) REFERENCES baby(id)
) ENGINE=InnoDB;

CREATE TABLE IF NOT EXISTS ai_message (
  id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  conversation_id BIGINT UNSIGNED NOT NULL,
  family_id BIGINT UNSIGNED NOT NULL,
  baby_id BIGINT UNSIGNED NOT NULL,
  author_user_id BIGINT UNSIGNED NULL,
  client_message_id CHAR(36) NULL,
  role VARCHAR(16) NOT NULL COMMENT 'USER/ASSISTANT',
  status VARCHAR(16) NOT NULL COMMENT 'PENDING/COMPLETED/FAILED',
  content TEXT NULL,
  snapshot_id BIGINT UNSIGNED NULL,
  error_code VARCHAR(48) NULL,
  search_used TINYINT(1) NOT NULL DEFAULT 0,
  created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  updated_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
  PRIMARY KEY (id),
  UNIQUE KEY uk_ai_message_request (conversation_id, role, client_message_id),
  KEY idx_ai_message_scope (family_id, baby_id, conversation_id, id),
  KEY idx_ai_message_author_rate (author_user_id, created_at),
  KEY idx_ai_message_snapshot (snapshot_id),
  CONSTRAINT fk_ai_message_conversation FOREIGN KEY (conversation_id) REFERENCES ai_conversation(id),
  CONSTRAINT fk_ai_message_family FOREIGN KEY (family_id) REFERENCES family(id),
  CONSTRAINT fk_ai_message_baby FOREIGN KEY (baby_id) REFERENCES baby(id),
  CONSTRAINT fk_ai_message_author FOREIGN KEY (author_user_id) REFERENCES app_user(id),
  CONSTRAINT fk_ai_message_snapshot FOREIGN KEY (snapshot_id) REFERENCES ai_snapshot(id)
) ENGINE=InnoDB;

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
