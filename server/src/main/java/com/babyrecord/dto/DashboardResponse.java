package com.babyrecord.dto;

import com.babyrecord.model.BabyEvent;
import java.util.List;

public record DashboardResponse(
        BabySummary baby,
        BabyEvent lastFeed,
        BabyEvent activeSleep,
        BabyEvent lastPoop,
        BabyEvent lastPee,
        List<Integer> feedQuickAmounts,
        TodaySummary today,
        List<BabyEvent> timeline
) {
    public record TodaySummary(
            int feedCount,
            int totalMilkMl,
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
