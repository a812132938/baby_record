package com.babyrecord.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;

import java.time.LocalDateTime;
import java.util.List;

public record FeedingRequest(
        @NotBlank String type,
        Integer amountMl,
        Integer leftSeconds,
        Integer rightSeconds,
        String lastSide,
        List<FeedingSegment> segments,
        Integer leftMl,
        Integer rightMl,
        Integer durationSeconds,
        LocalDateTime eventTime,
        @Pattern(regexp = "^[0-9a-fA-F-]{36}$") String clientEventId
) {
    public record FeedingSegment(String side, Integer seconds) {}
}
