package com.babyrecord.dto;

import java.time.LocalDateTime;
import java.util.Map;

public record AiSnapshotResponse(
        long id,
        LocalDateTime snapshotAt,
        LocalDateTime rangeStart,
        LocalDateTime rangeEnd,
        int sourceEventCount,
        String promptVersion,
        Map<String, Object> dashboard
) {}
