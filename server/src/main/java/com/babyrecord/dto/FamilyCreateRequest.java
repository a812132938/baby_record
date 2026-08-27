package com.babyrecord.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PastOrPresent;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import java.time.LocalDate;

public record FamilyCreateRequest(
        @NotBlank @Size(max = 64) String familyName,
        @NotBlank @Size(max = 64) String babyNickname,
        @NotNull @PastOrPresent LocalDate birthDate,
        @NotNull @Pattern(regexp = "^(BOY|GIRL)$") String gender,
        @NotNull @Min(100) @Max(15000) Integer birthWeightGrams,
        @NotBlank @Size(max = 64) String nickname,
        @NotBlank
        @Pattern(regexp = "(?i)^[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$")
        String creationKey,
        @NotBlank
        @Pattern(regexp = "^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$")
        String deviceId,
        @Size(max = 120) String deviceName
) {}
