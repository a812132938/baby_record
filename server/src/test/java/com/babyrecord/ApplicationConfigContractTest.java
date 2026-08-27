package com.babyrecord;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

class ApplicationConfigContractTest {

    @Test
    void deploymentSpecificAndSensitiveSettingsRequireEnvironmentValues() throws IOException {
        var yaml = Files.readString(Path.of("src/main/resources/application.yml"));

        assertThat(yaml)
                .contains("url: ${DB_URL}")
                .contains("username: ${DB_USERNAME}")
                .contains("password: ${DB_PASSWORD}")
                .contains("secure-cookie: ${APP_SECURE_COOKIE}")
                .contains("session-days: ${APP_AUTH_SESSION_DAYS}")
                .contains("creation-recovery-minutes: ${APP_AUTH_CREATION_RECOVERY_MINUTES}")
                .contains("allowed-origin-patterns: ${APP_CORS_ALLOWED_ORIGIN_PATTERNS}")
                .contains("enabled: ${AI_ENABLED:false}")
                .contains("api-key: ${DEEPSEEK_API_KEY:}")
                .contains("base-url: ${DEEPSEEK_BASE_URL:https://api.deepseek.com}")
                .contains("model: ${DEEPSEEK_MODEL:deepseek-v4-flash}")
                .contains("attempt-timeout-seconds: ${DEEPSEEK_ATTEMPT_TIMEOUT_SECONDS:90}")
                .contains("total-timeout-seconds: ${DEEPSEEK_TOTAL_TIMEOUT_SECONDS:90}")
                .contains("heartbeat-interval-ms: ${AI_STREAM_HEARTBEAT_INTERVAL_MS:10000}")
                .contains("heartbeat-interval-ms: ${REALTIME_HEARTBEAT_INTERVAL_MS:20000}")
                .doesNotContain("sk-")
                .doesNotContain("baby_dev_password")
                .doesNotContain("http://localhost:*")
                .doesNotContain("http://127.0.0.1:*");
    }

    @Test
    void familyCreationConfirmationIsProtectedByTheDeviceInterceptor() throws IOException {
        var webConfig = Files.readString(Path.of(
                "src/main/java/com/babyrecord/config/WebConfig.java"
        ));

        assertThat(webConfig)
                .contains("\"/api/v1/auth/family/create/confirm\"")
                .doesNotContain("\"/api/v1/auth/family/**\"");
    }
}
