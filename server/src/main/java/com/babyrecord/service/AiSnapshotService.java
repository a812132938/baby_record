package com.babyrecord.service;

import com.babyrecord.mapper.AiConversationMapper;
import com.babyrecord.model.AiAggregateRow;
import com.babyrecord.model.AiRecentEventRow;
import com.babyrecord.model.AiSnapshotRow;
import com.babyrecord.model.AiStoolBreakdownRow;
import org.springframework.stereotype.Service;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;

import java.time.Duration;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Service
public class AiSnapshotService {
    private static final String SNAPSHOT_SCHEMA_VERSION = "baby-ai-snapshot-v4";
    private static final DateTimeFormatter DATE_TIME = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm");
    private static final int MAX_PROVIDER_SNAPSHOT_CHARS = 30_000;
    private static final int COMPARISON_WINDOW_SIZE = 12;
    private static final List<String> INTAKE_TYPES = List.of(
            "FEED", "DIRECT_BREASTFEED", "BOTTLE_BREAST_MILK", "FORMULA_FEED"
    );

    private final AiConversationMapper mapper;
    private final AiPromptBuilder promptBuilder;
    private final ObjectMapper objectMapper;

    public AiSnapshotService(AiConversationMapper mapper, AiPromptBuilder promptBuilder, ObjectMapper objectMapper) {
        this.mapper = mapper;
        this.promptBuilder = promptBuilder;
        this.objectMapper = objectMapper;
    }

    public BuiltSnapshot create(long conversationId, long familyId, long babyId) {
        LocalDateTime cutoff = mapper.databaseNow(familyId, babyId);
        if (cutoff == null) throw new AiProviderException("AI_DATA_UNAVAILABLE");
        var baby = mapper.findBabyProfile(familyId, babyId);
        var aggregate = mapper.aggregate(familyId, babyId, cutoff);
        if (baby == null || aggregate == null) throw new AiProviderException("AI_DATA_UNAVAILABLE");

        List<AiRecentEventRow> feedingRows = new ArrayList<>(mapper.feedingEvents(familyId, babyId, cutoff));
        feedingRows.sort(Comparator.comparing(AiRecentEventRow::startTime).thenComparingLong(AiRecentEventRow::id));
        List<FeedingEvent> allFeedingEvents = sanitizeFeedingEvents(feedingRows);
        List<Map<String, Object>> recentEvents = mapper.recentEvents(familyId, babyId, cutoff).stream()
                .sorted(Comparator.comparing(AiRecentEventRow::startTime).thenComparingLong(AiRecentEventRow::id))
                .map(event -> sanitizeRecentEvent(event, cutoff))
                .toList();

        int coverageDays = aggregate.rangeStart() == null ? 0
                : Math.toIntExact(ChronoUnit.DAYS.between(aggregate.rangeStart().toLocalDate(), cutoff.toLocalDate()) + 1);
        int ageDays = Math.max(1,
                Math.toIntExact(ChronoUnit.DAYS.between(baby.birthday(), cutoff.toLocalDate())) + 1);
        var babyData = ordered("ageDays", ageDays, "gender", baby.gender(), "birthWeightGrams", baby.birthWeightGrams());
        long averageSleep = aggregate.completedSleepSessions() == 0 ? 0
                : aggregate.completedSleepMinutes() / aggregate.completedSleepSessions();
        var sleep = ordered(
                "completedSessions", aggregate.completedSleepSessions(), "ongoingSessions", aggregate.ongoingSleepSessions(),
                "totalMinutes", aggregate.sleepMinutes(), "averageMinutes", averageSleep,
                "longestMinutes", aggregate.longestSleepMinutes(), "currentSleepMinutes", aggregate.currentSleepMinutes()
        );
        var stool = ordered(
                "count", aggregate.stoolCount(),
                "byColor", breakdown(mapper.stoolColors(familyId, babyId, cutoff)),
                "byTexture", breakdown(mapper.stoolTextures(familyId, babyId, cutoff)),
                "byAmount", breakdown(mapper.stoolAmounts(familyId, babyId, cutoff))
        );

        List<FeedingEvent> includedFeeding = new ArrayList<>(allFeedingEvents);
        List<Map<String, Object>> includedRecent = new ArrayList<>(recentEvents);
        Map<String, Object> dashboard = dashboard(cutoff, aggregate, coverageDays, babyData, sleep, stool,
                allFeedingEvents, includedFeeding, includedRecent, recentEvents.size());
        if (json(dashboard).length() > MAX_PROVIDER_SNAPSHOT_CHARS) {
            int recentToKeep = largestFittingSuffix(recentEvents.size(), keep -> dashboard(cutoff, aggregate,
                    coverageDays, babyData, sleep, stool, allFeedingEvents, allFeedingEvents,
                    latest(recentEvents, keep), recentEvents.size()));
            includedRecent = latest(recentEvents, recentToKeep);
            dashboard = dashboard(cutoff, aggregate, coverageDays, babyData, sleep, stool,
                    allFeedingEvents, includedFeeding, includedRecent, recentEvents.size());
        }
        if (json(dashboard).length() > MAX_PROVIDER_SNAPSHOT_CHARS) {
            List<Map<String, Object>> finalRecent = includedRecent;
            int feedingToKeep = largestFittingSuffix(allFeedingEvents.size(), keep -> dashboard(cutoff, aggregate,
                    coverageDays, babyData, sleep, stool, allFeedingEvents, latest(allFeedingEvents, keep),
                    finalRecent, recentEvents.size()));
            includedFeeding = latest(allFeedingEvents, feedingToKeep);
            dashboard = dashboard(cutoff, aggregate, coverageDays, babyData, sleep, stool,
                    allFeedingEvents, includedFeeding, includedRecent, recentEvents.size());
        }

        String dashboardJson = json(dashboard);
        String promptText = promptBuilder.snapshotPrompt(dashboard);
        mapper.insertSnapshot(conversationId, familyId, babyId, cutoff, aggregate.rangeStart(), cutoff,
                aggregate.sourceEventCount(), AiPromptBuilder.PROMPT_VERSION, dashboardJson, promptText);
        AiSnapshotRow stored = mapper.findLatestSnapshot(conversationId, familyId, babyId);
        if (stored == null || !stored.snapshotAt().equals(cutoff)) throw new AiProviderException("AI_SNAPSHOT_WRITE_FAILED");
        return new BuiltSnapshot(stored, promptText);
    }

    private Map<String, Object> dashboard(LocalDateTime cutoff, AiAggregateRow aggregate, int coverageDays,
                                          Map<String, Object> baby, Map<String, Object> sleep,
                                          Map<String, Object> stool, List<FeedingEvent> allFeeding,
                                          List<FeedingEvent> includedFeeding,
                                          List<Map<String, Object>> recentEvents, int totalRecentEvents) {
        var feeding = feeding(aggregate, allFeeding, includedFeeding);
        List<String> qualityNotes = qualityNotes(aggregate.sourceEventCount(), aggregate.unclassifiedBottleCount(),
                aggregate.ongoingSleepSessions(), aggregate.stoolCount(), stool,
                totalRecentEvents, recentEvents.size(), allFeeding.size(), includedFeeding.size());
        return ordered(
                "schemaVersion", SNAPSHOT_SCHEMA_VERSION,
                "timezone", ZoneId.systemDefault().getId(), "snapshotAt", cutoff.format(DATE_TIME),
                "rangeStart", aggregate.rangeStart() == null ? null : aggregate.rangeStart().format(DATE_TIME),
                "rangeEnd", cutoff.format(DATE_TIME), "coverageDays", coverageDays,
                "sourceEventCount", aggregate.sourceEventCount(), "baby", baby, "feeding", feeding,
                "sleep", sleep, "stool", stool, "recentEvents", recentEvents, "qualityNotes", qualityNotes
        );
    }

    private int largestFittingSuffix(int size, java.util.function.IntFunction<Map<String, Object>> candidate) {
        int low = 0;
        int high = size;
        int best = -1;
        while (low <= high) {
            int middle = (low + high) >>> 1;
            if (json(candidate.apply(middle)).length() <= MAX_PROVIDER_SNAPSHOT_CHARS) {
                best = middle;
                low = middle + 1;
            } else {
                high = middle - 1;
            }
        }
        return Math.max(0, best);
    }

    private static <T> ArrayList<T> latest(List<T> values, int count) {
        return new ArrayList<>(values.subList(values.size() - count, values.size()));
    }

    private Map<String, Object> feeding(AiAggregateRow aggregate, List<FeedingEvent> allEvents,
                                        List<FeedingEvent> includedEvents) {
        var result = ordered(
                "totalRecords", aggregate.totalRecords(),
                "directBreastfeedCount", aggregate.directBreastfeedCount(),
                "directBreastfeedMinutes", aggregate.directBreastfeedSeconds() / 60,
                "bottleBreastMilkCount", aggregate.bottleBreastMilkCount(),
                "bottleBreastMilkMl", aggregate.bottleBreastMilkMl(),
                "formulaFeedCount", aggregate.formulaFeedCount(), "formulaFeedMl", aggregate.formulaFeedMl(),
                "unclassifiedBottleCount", aggregate.unclassifiedBottleCount(),
                "unclassifiedBottleMl", aggregate.unclassifiedBottleMl(),
                "pumpingCount", aggregate.pumpingCount(), "pumpingMl", aggregate.pumpingMl(),
                "pumpingMinutes", aggregate.pumpingSeconds() / 60, "rhythm", rhythm(allEvents)
        );
        result.put("intakeTimeline", timelineOf(includedEvents, true));
        result.put("pumpingTimeline", timelineOf(includedEvents, false));
        result.put("eventWindows", eventWindows(allEvents));
        result.put("longTermBaseline", intervalStats(intakeEvents(allEvents)));
        boolean truncated = includedEvents.size() < allEvents.size();
        result.put("recordCoverage", ordered(
                "total", allEvents.size(), "included", includedEvents.size(), "truncated", truncated,
                "omittedBefore", truncated && !includedEvents.isEmpty()
                        ? includedEvents.getFirst().recordedAt().format(DATE_TIME) : null
        ));
        return result;
    }

    private Map<String, Object> rhythm(List<FeedingEvent> allEvents) {
        List<Long> intervals = allEvents.stream().filter(event -> INTAKE_TYPES.contains(event.type()))
                .map(FeedingEvent::minutesSincePreviousFeed).filter(value -> value != null).toList();
        long total = intervals.stream().mapToLong(Long::longValue).sum();
        long intakeCount = allEvents.stream().filter(event -> INTAKE_TYPES.contains(event.type())).count();
        return ordered(
                "intakeFeedCount", intakeCount, "intervalCount", intervals.size(),
                "averageIntervalMinutes", intervals.isEmpty() ? null : Math.round((double) total / intervals.size()),
                "shortestIntervalMinutes", intervals.stream().min(Long::compareTo).orElse(null),
                "longestIntervalMinutes", intervals.stream().max(Long::compareTo).orElse(null)
        );
    }

    private List<Map<String, Object>> timelineOf(List<FeedingEvent> events, boolean intake) {
        return events.stream()
                .filter(event -> intake == INTAKE_TYPES.contains(event.type()))
                .map(FeedingEvent::data)
                .toList();
    }

    private Map<String, Object> eventWindows(List<FeedingEvent> allEvents) {
        List<FeedingEvent> intakeEvents = intakeEvents(allEvents);
        int recentStart = Math.max(0, intakeEvents.size() - COMPARISON_WINDOW_SIZE);
        int priorStart = Math.max(0, recentStart - COMPARISON_WINDOW_SIZE);
        List<FeedingEvent> recent = intakeEvents.subList(recentStart, intakeEvents.size());
        List<FeedingEvent> prior = intakeEvents.subList(priorStart, recentStart);
        boolean comparable = recent.size() == COMPARISON_WINDOW_SIZE && prior.size() == COMPARISON_WINDOW_SIZE;
        String reason = comparable ? null : "至少需要24条摄入记录，才能比较最近12条与此前12条";
        return ordered(
                "windowSize", COMPARISON_WINDOW_SIZE,
                "comparable", comparable,
                "notComparableReason", reason,
                "recent", comparisonWindow(recent, comparable, reason),
                "prior", comparisonWindow(prior, comparable, reason)
        );
    }

    private Map<String, Object> comparisonWindow(List<FeedingEvent> events, boolean comparable, String reason) {
        Map<String, Object> result = intervalStats(events);
        FeedingEvent first = events.isEmpty() ? null : events.getFirst();
        result.put("leadingIntervalMinutes", first == null ? null : first.minutesSincePreviousFeed());
        result.put("leadingIntervalFrom", first == null || first.previousRecordedAt() == null
                ? null : first.previousRecordedAt().format(DATE_TIME));
        result.put("leadingIntervalTo", first == null || first.previousRecordedAt() == null
                ? null : first.recordedAt().format(DATE_TIME));
        result.put("comparable", comparable);
        result.put("notComparableReason", reason);
        return result;
    }

    private Map<String, Object> intervalStats(List<FeedingEvent> events) {
        List<Interval> intervals = new ArrayList<>();
        for (int i = 1; i < events.size(); i++) {
            FeedingEvent previous = events.get(i - 1);
            FeedingEvent current = events.get(i);
            intervals.add(new Interval(
                    Math.max(0, Duration.between(previous.recordedAt(), current.recordedAt()).toMinutes()),
                    previous.recordedAt(), current.recordedAt()
            ));
        }
        List<Long> sorted = intervals.stream().map(Interval::minutes).sorted().toList();
        Interval shortest = intervals.stream().min(Comparator.comparingLong(Interval::minutes)).orElse(null);
        Interval longest = intervals.stream().max(Comparator.comparingLong(Interval::minutes)).orElse(null);
        return ordered(
                "eventCount", events.size(),
                "sampleCount", intervals.size(),
                "median", percentile(sorted, 0.50),
                "p25", percentile(sorted, 0.25),
                "p75", percentile(sorted, 0.75),
                "shortest", shortest == null ? null : shortest.minutes(),
                "shortestFrom", shortest == null ? null : shortest.from().format(DATE_TIME),
                "shortestTo", shortest == null ? null : shortest.to().format(DATE_TIME),
                "longest", longest == null ? null : longest.minutes(),
                "longestFrom", longest == null ? null : longest.from().format(DATE_TIME),
                "longestTo", longest == null ? null : longest.to().format(DATE_TIME)
        );
    }

    private static Long percentile(List<Long> sorted, double percentile) {
        if (sorted.isEmpty()) return null;
        double index = percentile * (sorted.size() - 1);
        int lower = (int) Math.floor(index);
        int upper = (int) Math.ceil(index);
        if (lower == upper) return sorted.get(lower);
        double interpolated = sorted.get(lower) + (sorted.get(upper) - sorted.get(lower)) * (index - lower);
        return Math.round(interpolated);
    }

    private static List<FeedingEvent> intakeEvents(List<FeedingEvent> events) {
        return events.stream().filter(event -> INTAKE_TYPES.contains(event.type())).toList();
    }

    private List<FeedingEvent> sanitizeFeedingEvents(List<AiRecentEventRow> events) {
        var result = new ArrayList<FeedingEvent>();
        LocalDateTime previousIntakeStart = null;
        for (var event : events) {
            if (!INTAKE_TYPES.contains(event.eventType()) && !"PUMPING".equals(event.eventType())) continue;
            Long interval = INTAKE_TYPES.contains(event.eventType()) && previousIntakeStart != null
                    ? Math.max(0, Duration.between(previousIntakeStart, event.startTime()).toMinutes()) : null;
            var data = new LinkedHashMap<String, Object>();
            data.put("type", event.eventType());
            data.put("recordedAt", event.startTime().format(DATE_TIME));
            if (INTAKE_TYPES.contains(event.eventType())) {
                data.put("previousRecordedAt", previousIntakeStart == null ? null : previousIntakeStart.format(DATE_TIME));
                data.put("minutesSincePreviousFeed", interval);
            }
            switch (event.eventType()) {
                case "FEED", "BOTTLE_BREAST_MILK", "FORMULA_FEED" -> data.put("amountMl", event.amountMl());
                case "DIRECT_BREASTFEED" -> {
                    var raw = eventData(event.eventData());
                    double leftMinutes = number(raw.get("leftSeconds")) / 60.0;
                    double rightMinutes = number(raw.get("rightSeconds")) / 60.0;
                    data.put("durationMinutes", leftMinutes + rightMinutes);
                    data.put("leftMinutes", leftMinutes);
                    data.put("rightMinutes", rightMinutes);
                }
                case "PUMPING" -> {
                    var raw = eventData(event.eventData());
                    data.put("leftMl", number(raw.get("leftMl")));
                    data.put("rightMl", number(raw.get("rightMl")));
                    data.put("amountMl", event.amountMl());
                    data.put("durationMinutes", number(raw.get("durationSeconds")) / 60.0);
                    data.put("notBabyIntake", true);
                }
                default -> throw new IllegalStateException("Unexpected feeding event type");
            }
            result.add(new FeedingEvent(event.eventType(), event.startTime(), previousIntakeStart, interval, data));
            if (INTAKE_TYPES.contains(event.eventType())) previousIntakeStart = event.startTime();
        }
        return result;
    }

    private Map<String, Object> sanitizeRecentEvent(AiRecentEventRow event, LocalDateTime cutoff) {
        var result = new LinkedHashMap<String, Object>();
        result.put("type", event.eventType());
        result.put("recordedAt", event.startTime().format(DATE_TIME));
        switch (event.eventType()) {
            case "SLEEP" -> {
                LocalDateTime end = event.endTime() == null || event.endTime().isAfter(cutoff) ? cutoff : event.endTime();
                result.put("endAt", end.format(DATE_TIME));
                result.put("durationMinutes", Math.max(0, Duration.between(event.startTime(), end).toMinutes()));
                result.put("ongoing", event.endTime() == null);
            }
            case "POOP" -> {
                var raw = eventData(event.eventData());
                result.put("color", whitelisted(raw.get("color"), List.of("黄色", "黄绿色", "绿色", "棕色")));
                result.put("texture", whitelisted(raw.get("texture"), List.of("奶瓣", "糊状", "稀", "水样")));
                result.put("amount", whitelisted(raw.get("amount"), List.of("少", "中", "多")));
            }
            default -> throw new IllegalStateException("Unexpected recent AI event type");
        }
        return result;
    }

    private List<String> qualityNotes(int sourceCount, int unclassifiedCount, int ongoingSleep, int stoolCount,
                                      Map<String, Object> stool, int totalRecentCount, int includedRecentCount,
                                      int totalFeedingCount, int includedFeedingCount) {
        var notes = new ArrayList<String>();
        if (sourceCount == 0) notes.add("当前没有可分析的喂养、睡眠或大便记录");
        if (sourceCount > 0 && sourceCount < 10) notes.add("样本量较少，趋势判断可靠性有限");
        if (unclassifiedCount > 0) notes.add("存在未分类瓶喂记录，无法区分母乳或配方奶");
        if (ongoingSleep > 0) notes.add("存在进行中的睡眠，其时长按快照时点截断");
        if (stoolCount > 0 && (((Map<?, ?>) stool.get("byColor")).containsKey("未记录")
                || ((Map<?, ?>) stool.get("byTexture")).containsKey("未记录")
                || ((Map<?, ?>) stool.get("byAmount")).containsKey("未记录"))) {
            notes.add("部分大便记录缺少颜色、性状或量级");
        }
        if (includedRecentCount < totalRecentCount) notes.add("近期睡眠和大便明细因上下文长度限制仅保留最新记录");
        if (includedFeedingCount < totalFeedingCount) {
            notes.add("喂养明细因上下文长度限制仅保留最新记录；节律统计和汇总仍基于全量记录");
        }
        notes.add("喂养间隔仅按相邻已记录喂养的开始时间计算，漏记不代表期间没有喂养");
        notes.add("亲喂时长未换算为毫升，泵奶量未计入宝宝摄入量");
        return notes;
    }

    private Map<String, Integer> breakdown(List<AiStoolBreakdownRow> rows) {
        var result = new LinkedHashMap<String, Integer>();
        for (var row : rows) result.put(row.value(), row.count());
        return result;
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> eventData(String value) {
        if (value == null || value.isBlank()) return Map.of();
        try {
            return objectMapper.readValue(value, Map.class);
        } catch (JacksonException e) {
            return Map.of();
        }
    }

    private String json(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JacksonException e) {
            throw new AiProviderException("AI_SNAPSHOT_INVALID");
        }
    }

    private static long number(Object value) {
        return value instanceof Number number ? number.longValue() : 0;
    }

    private static String whitelisted(Object value, List<String> allowed) {
        if (!(value instanceof String string) || string.isBlank()) return "未记录";
        return allowed.contains(string) ? string : "其他";
    }

    private static LinkedHashMap<String, Object> ordered(Object... pairs) {
        var result = new LinkedHashMap<String, Object>();
        for (int i = 0; i < pairs.length; i += 2) result.put((String) pairs[i], pairs[i + 1]);
        return result;
    }

    private record FeedingEvent(String type, LocalDateTime recordedAt, LocalDateTime previousRecordedAt,
                                Long minutesSincePreviousFeed,
                                Map<String, Object> data) {}

    private record Interval(long minutes, LocalDateTime from, LocalDateTime to) {}

    public record BuiltSnapshot(AiSnapshotRow row, String promptText) {}
}
