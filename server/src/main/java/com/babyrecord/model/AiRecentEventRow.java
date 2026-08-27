package com.babyrecord.model;

import java.time.LocalDateTime;

public record AiRecentEventRow(
        long id,
        String eventType,
        LocalDateTime startTime,
        LocalDateTime endTime,
        Integer amountMl,
        String eventData
) {}
