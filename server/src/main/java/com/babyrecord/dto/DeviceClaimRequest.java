package com.babyrecord.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

public record DeviceClaimRequest(
        @NotBlank @Size(max = 32) String inviteCode,
        @NotBlank @Size(max = 32) String nickname,
        @NotBlank @Pattern(regexp = "^[0-9a-fA-F-]{36}$") String deviceId,
        @Size(max = 120) String deviceName
) {}
