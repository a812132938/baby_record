package com.babyrecord.dto;

import java.time.LocalDateTime;

public record AiConversationSummary(
        long id,
        String title,
        String status,
        String model,
        String lastErrorCode,
        LocalDateTime createdAt,
        LocalDateTime updatedAt,
        AiSnapshotResponse latestSnapshot
) {}
