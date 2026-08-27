package com.babyrecord.dto;

public record MeResponse(long deviceId, long userId, long familyId, long babyId, String nickname, String role) {}
