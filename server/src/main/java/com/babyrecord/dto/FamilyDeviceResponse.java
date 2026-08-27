package com.babyrecord.dto;

import java.time.LocalDateTime;

public record FamilyDeviceResponse(
        long id,
        long userId,
        String nickname,
        String role,
        String deviceName,
        LocalDateTime lastActiveAt,
        LocalDateTime createdAt,
        boolean revoked
) {}
