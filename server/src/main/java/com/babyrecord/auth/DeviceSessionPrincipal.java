package com.babyrecord.auth;

public record DeviceSessionPrincipal(
        long deviceId,
        long userId,
        long familyId,
        String nickname,
        String role
) {}
