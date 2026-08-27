package com.babyrecord.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;

import java.time.LocalDateTime;
import java.util.Map;

public record UpdateEventRequest(
        LocalDateTime eventTime,
        LocalDateTime endTime,
        @Min(1) @Max(1000) Integer amountMl,
        Map<String, Object> data,
        LocalDateTime expectedUpdatedAt
) {}
