package com.babyrecord;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

class InitSqlContractTest {

    @Test
    void initializationCreatesSchemaWithoutBusinessData() throws IOException {
        var sql = Files.readString(findProjectFile("sql/init.sql")).toUpperCase();

        assertThat(sql)
                .doesNotContain("CREATE DATABASE")
                .doesNotContain("USE BABY_RECORD")
                .doesNotContain("INSERT INTO APP_USER")
                .doesNotContain("INSERT INTO FAMILY (")
                .doesNotContain("INSERT INTO FAMILY_MEMBER")
                .doesNotContain("INSERT INTO BABY (")
                .doesNotContain("BABY-2026")
                .doesNotContain("CURRENT_DATE - INTERVAL")
                .doesNotContain("SELECT '宝宝家庭'")
                .doesNotContain("INITIAL_INVITE_CODE")
                .doesNotContain("RANDOM_BYTES")
                .contains("BIRTHDAY DATE NOT NULL")
                .contains("GENDER VARCHAR(16) NOT NULL")
                .contains("BIRTH_WEIGHT_GRAMS SMALLINT UNSIGNED NOT NULL")
                .contains("CREATE TABLE IF NOT EXISTS FAMILY_CREATION_RECOVERY")
                .contains("RECOVERY_KEY_HASH CHAR(64) NOT NULL")
                .contains("EXPIRES_AT DATETIME(3) NOT NULL")
                .contains("IDX_FAMILY_CREATION_RECOVERY_EXPIRES")
                .contains("BABY_PROFILE_BEFORE_INSERT")
                .contains("BABY_PROFILE_BEFORE_UPDATE")
                .contains("NEW.BIRTHDAY > CURRENT_DATE()")
                .contains("BABY_EVENT_AMOUNT_BEFORE_INSERT")
                .contains("BABY_EVENT_AMOUNT_BEFORE_UPDATE")
                .contains("END_OPERATOR_ID BIGINT UNSIGNED NULL")
                .contains("IDX_END_OPERATOR_TIME")
                .contains("FK_BABY_EVENT_END_OPERATOR")
                .contains("FOREIGN KEY (END_OPERATOR_ID) REFERENCES APP_USER(ID)")
                .contains("DIRECT_BREASTFEED")
                .contains("BOTTLE_BREAST_MILK")
                .contains("FORMULA_FEED")
                .contains("PUMPING")
                .contains("$.LEFTSECONDS")
                .contains("$.RIGHTSECONDS")
                .contains("$.LASTSIDE")
                .contains("$.LEFTML")
                .contains("$.RIGHTML")
                .contains("$.DURATIONSECONDS")
                .contains("SIGNAL SQLSTATE '45000'");
    }

    @Test
    void feedingMigrationIsRepeatableAndDoesNotRewriteHistory() throws IOException {
        var sql = Files.readString(findProjectFile(
                "sql/migrations/20260821_01_feeding_event_types.sql"
        )).toUpperCase();

        assertThat(sql)
                .contains("DROP TRIGGER IF EXISTS BABY_EVENT_AMOUNT_BEFORE_INSERT")
                .contains("DROP TRIGGER IF EXISTS BABY_EVENT_AMOUNT_BEFORE_UPDATE")
                .contains("CREATE TRIGGER BABY_EVENT_AMOUNT_BEFORE_INSERT")
                .contains("CREATE TRIGGER BABY_EVENT_AMOUNT_BEFORE_UPDATE")
                .contains("DIRECT_BREASTFEED")
                .contains("BOTTLE_BREAST_MILK")
                .contains("FORMULA_FEED")
                .contains("PUMPING")
                .doesNotContain("UPDATE BABY_EVENT")
                .doesNotContain("DELETE FROM BABY_EVENT")
                .doesNotContain("INSERT INTO BABY_EVENT");
    }

    @Test
    void sleepEndOperatorMigrationIsRepeatableAndPreservesExistingSleeps() throws IOException {
        var sql = Files.readString(findProjectFile(
                "sql/migrations/20260821_02_sleep_end_operator.sql"
        )).toUpperCase();

        assertThat(sql)
                .contains("INFORMATION_SCHEMA.COLUMNS")
                .contains("INFORMATION_SCHEMA.STATISTICS")
                .contains("INFORMATION_SCHEMA.TABLE_CONSTRAINTS")
                .contains("PREPARE SLEEP_END_ADD_COLUMN")
                .contains("PREPARE SLEEP_END_ADD_INDEX")
                .contains("PREPARE SLEEP_END_ADD_FK")
                .contains("ADD COLUMN END_OPERATOR_ID BIGINT UNSIGNED NULL")
                .contains("ADD INDEX IDX_END_OPERATOR_TIME")
                .contains("FOREIGN KEY (END_OPERATOR_ID) REFERENCES APP_USER(ID)")
                .doesNotContain("UPDATE BABY_EVENT")
                .doesNotContain("DELETE FROM BABY_EVENT")
                .doesNotContain("INSERT INTO BABY_EVENT");
    }

    @Test
    void aiConversationMigrationIsRepeatableScopedAndKeepsImmutableSnapshots() throws IOException {
        var migration = Files.readString(findProjectFile(
                "sql/migrations/20260821_03_ai_conversations.sql"
        )).toUpperCase();
        var init = Files.readString(findProjectFile("sql/init.sql")).toUpperCase();

        assertThat(migration)
                .contains("CREATE TABLE IF NOT EXISTS AI_CONVERSATION")
                .contains("CREATE TABLE IF NOT EXISTS AI_SNAPSHOT")
                .contains("CREATE TABLE IF NOT EXISTS AI_MESSAGE")
                .contains("FAMILY_ID BIGINT UNSIGNED NOT NULL")
                .contains("BABY_ID BIGINT UNSIGNED NOT NULL")
                .contains("UK_AI_CONVERSATION_REQUEST")
                .contains("UK_AI_MESSAGE_REQUEST (CONVERSATION_ID, ROLE, CLIENT_MESSAGE_ID)")
                .contains("IDX_AI_CONVERSATION_SCOPE")
                .contains("IDX_AI_SNAPSHOT_SCOPE")
                .contains("IDX_AI_MESSAGE_SCOPE")
                .contains("DATA_PROCESSING_ACCEPTED_AT DATETIME(3) NOT NULL")
                .contains("DASHBOARD JSON NOT NULL")
                .contains("PROMPT_TEXT MEDIUMTEXT NOT NULL")
                .contains("ARCHIVED_AT DATETIME(3) NULL")
                .doesNotContain("DROP TABLE")
                .doesNotContain("DELETE FROM")
                .doesNotContain("UPDATE AI_SNAPSHOT");
        assertThat(init)
                .contains("CREATE TABLE IF NOT EXISTS AI_CONVERSATION")
                .contains("CREATE TABLE IF NOT EXISTS AI_SNAPSHOT")
                .contains("CREATE TABLE IF NOT EXISTS AI_MESSAGE")
                .contains("SEARCH_USED TINYINT(1) NOT NULL DEFAULT 0");
    }

    @Test
    void aiSearchMetadataMigrationIsRepeatableAndPreservesMessages() throws IOException {
        var migration = Files.readString(findProjectFile(
                "sql/migrations/20260822_04_ai_search_metadata.sql"
        )).toUpperCase();

        assertThat(migration)
                .contains("INFORMATION_SCHEMA.COLUMNS")
                .contains("COLUMN_NAME = 'SEARCH_USED'")
                .contains("ADD COLUMN SEARCH_USED TINYINT(1) NOT NULL DEFAULT 0")
                .contains("PREPARE AI_SEARCH_USED_STATEMENT")
                .doesNotContain("DELETE FROM")
                .doesNotContain("UPDATE AI_MESSAGE")
                .doesNotContain("DROP TABLE");
    }

    @Test
    void legacyMigrationsPreserveUnknownBirthProfileValues() throws IOException {
        var recoverySql = Files.readString(findProjectFile(
                "sql/migrations/20260820_01_family_creation_recovery.sql"
        )).toUpperCase();
        var profileSql = Files.readString(findProjectFile(
                "sql/migrations/20260820_02_baby_birth_profile.sql"
        )).toUpperCase();
        var constraintSql = Files.readString(findProjectFile(
                "sql/migrations/20260820_03_require_complete_baby_profile.sql"
        )).toUpperCase();

        assertThat(recoverySql)
                .contains("CREATE TABLE IF NOT EXISTS FAMILY_CREATION_RECOVERY")
                .contains("ADD COLUMN EXPIRES_AT DATETIME(3) NULL")
                .contains("DATE_ADD(CREATED_AT, INTERVAL 30 MINUTE)")
                .contains("WHERE EXPIRES_AT IS NULL")
                .contains("MODIFY EXPIRES_AT DATETIME(3) NOT NULL")
                .contains("INFORMATION_SCHEMA.STATISTICS")
                .contains("IDX_FAMILY_CREATION_RECOVERY_EXPIRES");
        assertThat(profileSql)
                .contains("ADD COLUMN GENDER VARCHAR(16) NULL")
                .contains("ADD COLUMN BIRTH_WEIGHT_GRAMS SMALLINT UNSIGNED NULL")
                .contains("BABY_PROFILE_BEFORE_INSERT")
                .contains("BABY_PROFILE_BEFORE_UPDATE")
                .contains("NEW.BIRTHDAY > CURRENT_DATE()")
                .doesNotContain("UPDATE BABY");
        assertThat(constraintSql)
                .contains("SIGNAL SQLSTATE '45000'")
                .contains("BIRTHDAY > CURRENT_DATE()")
                .contains("GENDER NOT IN ('BOY', 'GIRL')")
                .contains("BIRTH_WEIGHT_GRAMS < 100")
                .contains("BIRTH_WEIGHT_GRAMS > 15000")
                .contains("MODIFY BIRTHDAY DATE NOT NULL")
                .contains("MODIFY GENDER VARCHAR(16) NOT NULL")
                .contains("MODIFY BIRTH_WEIGHT_GRAMS SMALLINT UNSIGNED NOT NULL")
                .doesNotContain("UPDATE BABY");
    }

    private Path findProjectFile(String relativePath) {
        var workingDirectory = Path.of(System.getProperty("user.dir"));
        var fromProjectRoot = workingDirectory.resolve(relativePath);
        return Files.exists(fromProjectRoot) ? fromProjectRoot : workingDirectory.resolve("../").resolve(relativePath);
    }
}
