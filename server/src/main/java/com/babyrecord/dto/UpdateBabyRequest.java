package com.babyrecord.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PastOrPresent;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import java.time.LocalDate;

public record UpdateBabyRequest(
        @NotBlank @Size(max = 64) String nickname,
        @NotNull @PastOrPresent LocalDate birthday,
        @NotNull @Pattern(regexp = "^(BOY|GIRL)$") String gender,
        @NotNull @Min(100) @Max(15000) Integer birthWeightGrams
) {}
