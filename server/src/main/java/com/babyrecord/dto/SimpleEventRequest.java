package com.babyrecord.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import java.time.LocalDateTime;
import java.util.Map;

public record SimpleEventRequest(
        @NotBlank String type,
        LocalDateTime eventTime,
        @Pattern(regexp = "^[0-9a-fA-F-]{36}$") String clientEventId,
        Map<String, Object> data
) {}
