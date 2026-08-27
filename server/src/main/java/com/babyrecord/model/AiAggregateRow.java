package com.babyrecord.model;

import java.time.LocalDateTime;

public record AiAggregateRow(
        LocalDateTime rangeStart,
        int sourceEventCount,
        int totalRecords,
        int directBreastfeedCount,
        long directBreastfeedSeconds,
        int bottleBreastMilkCount,
        long bottleBreastMilkMl,
        int formulaFeedCount,
        long formulaFeedMl,
        int unclassifiedBottleCount,
        long unclassifiedBottleMl,
        int pumpingCount,
        long pumpingMl,
        long pumpingSeconds,
        int completedSleepSessions,
        int ongoingSleepSessions,
        long sleepMinutes,
        long completedSleepMinutes,
        long longestSleepMinutes,
        long currentSleepMinutes,
        int stoolCount
) {}
