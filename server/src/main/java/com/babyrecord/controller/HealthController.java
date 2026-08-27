package com.babyrecord.controller;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

@RestController
public class HealthController {
    private final boolean aiEnabled;

    public HealthController(@Value("${app.ai.enabled:false}") boolean aiEnabled,
                            @Value("${app.ai.deepseek.api-key:}") String aiApiKey) {
        this.aiEnabled = aiEnabled && aiApiKey != null && !aiApiKey.isBlank();
    }

    @GetMapping("/api/v1/health")
    public Map<String, String> health() {
        return Map.of("status", "ok");
    }

    @GetMapping("/api/v1/capabilities")
    public Map<String, Boolean> capabilities() {
        return Map.of("aiEnabled", aiEnabled);
    }
}
