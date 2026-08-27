package com.babyrecord.dto;

import java.util.List;

public record StatsResponse(List<Day> days) {
    public record Day(
            String date,
            String label,
            int milkMl,
            int feedCount,
            int sleepMinutes,
            int poopCount,
            int peeCount,
            int directBreastfeedCount,
            int directBreastfeedMinutes,
            int bottleBreastMilkCount,
            int bottleBreastMilkMl,
            int formulaFeedCount,
            int formulaFeedMl,
            int pumpingCount,
            int pumpingMl,
            int pumpingMinutes
    ) {}
}
