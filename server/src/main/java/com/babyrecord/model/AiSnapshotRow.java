package com.babyrecord.model;

import java.time.LocalDateTime;

public record AiSnapshotRow(
        long id,
        long conversationId,
        long familyId,
        long babyId,
        LocalDateTime snapshotAt,
        LocalDateTime rangeStart,
        LocalDateTime rangeEnd,
        int sourceEventCount,
        String promptVersion,
        String dashboard,
        String promptText,
        LocalDateTime createdAt
) {}
