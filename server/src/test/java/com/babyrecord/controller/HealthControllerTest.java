package com.babyrecord.controller;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class HealthControllerTest {

    @Test
    void reportsAiOnlyWhenExplicitlyEnabledAndConfigured() {
        assertThat(new HealthController(true, "provider-key").capabilities())
                .containsEntry("aiEnabled", true);
        assertThat(new HealthController(false, "provider-key").capabilities())
                .containsEntry("aiEnabled", false);
        assertThat(new HealthController(true, "  ").capabilities())
                .containsEntry("aiEnabled", false);
    }
}
