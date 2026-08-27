package com.babyrecord.model;

import java.time.LocalDateTime;

public record AiConversationRow(
        long id,
        long familyId,
        long babyId,
        long createdBy,
        String clientRequestId,
        String title,
        String status,
        String model,
        String lastErrorCode,
        LocalDateTime dataProcessingAcceptedAt,
        LocalDateTime archivedAt,
        LocalDateTime createdAt,
        LocalDateTime updatedAt
) {}
