package com.babyrecord.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import java.time.LocalDateTime;

public record FeedRequest(
        @NotNull @Min(1) @Max(1000) Integer amountMl,
        LocalDateTime eventTime,
        @Pattern(regexp = "^[0-9a-fA-F-]{36}$") String clientEventId
) {}
