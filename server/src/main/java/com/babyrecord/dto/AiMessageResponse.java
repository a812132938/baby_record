package com.babyrecord.dto;

import java.time.LocalDateTime;

public record AiMessageResponse(
        long id,
        String role,
        String status,
        String content,
        String authorName,
        Long snapshotId,
        LocalDateTime snapshotAt,
        LocalDateTime createdAt,
        String errorCode,
        boolean searchUsed
) {}
