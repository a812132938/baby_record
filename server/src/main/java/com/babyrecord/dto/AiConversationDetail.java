package com.babyrecord.dto;

import java.time.LocalDateTime;
import java.util.List;

public record AiConversationDetail(
        long id,
        String title,
        String status,
        String model,
        String lastErrorCode,
        LocalDateTime createdAt,
        LocalDateTime updatedAt,
        AiSnapshotResponse latestSnapshot,
        List<AiMessageResponse> messages
) {}
