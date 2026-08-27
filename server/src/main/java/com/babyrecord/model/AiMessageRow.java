package com.babyrecord.model;

import java.time.LocalDateTime;

public record AiMessageRow(
        long id,
        long conversationId,
        long familyId,
        long babyId,
        Long authorUserId,
        String clientMessageId,
        String role,
        String status,
        String content,
        Long snapshotId,
        String errorCode,
        boolean searchUsed,
        String authorName,
        LocalDateTime snapshotAt,
        LocalDateTime createdAt,
        LocalDateTime updatedAt
) {}
