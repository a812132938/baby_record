package com.babyrecord.dto;

import java.time.LocalDate;

public record BabySummary(
        long id,
        String nickname,
        LocalDate birthday,
        String gender,
        Integer birthWeightGrams
) {}
