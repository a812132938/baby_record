package com.babyrecord.service;

import com.babyrecord.dto.BabySummary;
import com.babyrecord.dto.DashboardResponse;
import com.babyrecord.dto.FeedingRequest;
import com.babyrecord.dto.StatsResponse;
import com.babyrecord.dto.UpdateBabyRequest;
import com.babyrecord.dto.UpdateEventRequest;
import com.babyrecord.mapper.BabyEventMapper;
import com.babyrecord.model.BabyEvent;
import com.babyrecord.realtime.RealtimeHub;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.web.server.ResponseStatusException;

import java.time.LocalDateTime;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.LinkedHashMap;
import java.util.Map;

@Service
public class BabyEventService {
    private static final String DIRECT_BREASTFEED = "DIRECT_BREASTFEED";
    private static final String BOTTLE_BREAST_MILK = "BOTTLE_BREAST_MILK";
    private static final String FORMULA_FEED = "FORMULA_FEED";
    private static final String PUMPING = "PUMPING";
    private static final List<String> FEEDING_TYPES = List.of(
            DIRECT_BREASTFEED, BOTTLE_BREAST_MILK, FORMULA_FEED, PUMPING
    );
    private final BabyEventMapper mapper;
    private final RealtimeHub realtimeHub;
    private final ObjectMapper objectMapper;

    public BabyEventService(BabyEventMapper mapper,
                            RealtimeHub realtimeHub,
                            ObjectMapper objectMapper) {
        this.mapper = mapper;
        this.realtimeHub = realtimeHub;
        this.objectMapper = objectMapper;
    }

    public void assertBabyAccess(long babyId, long familyId) {
        if (mapper.countBabyInFamily(babyId, familyId) != 1) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "无权访问这个宝宝");
        }
    }

    public DashboardResponse dashboard(long babyId, long familyId) {
        assertBabyAccess(babyId, familyId);
        var quickAmounts = mapper.findPopularFeedAmounts(babyId);
        var today = new DashboardResponse.TodaySummary(
                mapper.countFeedingsToday(babyId),
                mapper.sumMilkToday(babyId),
                mapper.countToday(babyId, "POOP"),
                mapper.countToday(babyId, "PEE"),
                mapper.countToday(babyId, DIRECT_BREASTFEED),
                secondsToMinutes(mapper.sumDirectBreastfeedSecondsToday(babyId)),
                mapper.countToday(babyId, BOTTLE_BREAST_MILK),
                mapper.sumAmountToday(babyId, BOTTLE_BREAST_MILK),
                mapper.countToday(babyId, FORMULA_FEED),
                mapper.sumAmountToday(babyId, FORMULA_FEED),
                mapper.countToday(babyId, PUMPING),
                mapper.sumAmountToday(babyId, PUMPING),
                secondsToMinutes(mapper.sumPumpingSecondsToday(babyId))
        );
        return new DashboardResponse(
                mapper.findBabySummary(babyId),
                mapper.findLastFeeding(babyId),
                mapper.findActiveSleep(babyId),
                mapper.findLastByType(babyId, "POOP"),
                mapper.findLastByType(babyId, "PEE"),
                quickAmounts,
                today,
                mapper.findTimeline(babyId, 60)
        );
    }

    public List<BabyEvent> history(long babyId, long familyId, LocalDate date) {
        assertBabyAccess(babyId, familyId);
        LocalDateTime start = date.atStartOfDay();
        LocalDateTime end = date.plusDays(1).atStartOfDay();
        return mapper.findEventsForRange(babyId, start, end);
    }

    public StatsResponse stats(long babyId, long familyId, int days) {
        assertBabyAccess(babyId, familyId);
        int normalizedDays = Math.max(1, Math.min(days, 31));
        LocalDate today = LocalDate.now();
        var rows = mapper.findDailyStats(babyId, normalizedDays - 1);
        var result = rows.stream().map(row -> {
            LocalDate date = LocalDate.parse(row.getDate(), DateTimeFormatter.ISO_LOCAL_DATE);
            String label = date.equals(today) ? "今天" : date.getMonthValue() + "/" + date.getDayOfMonth();
            return new StatsResponse.Day(
                    row.getDate(), label,
                    value(row.getMilkMl()), value(row.getFeedCount()), value(row.getSleepMinutes()),
                    value(row.getPoopCount()), value(row.getPeeCount()),
                    value(row.getDirectBreastfeedCount()), value(row.getDirectBreastfeedMinutes()),
                    value(row.getBottleBreastMilkCount()), value(row.getBottleBreastMilkMl()),
                    value(row.getFormulaFeedCount()), value(row.getFormulaFeedMl()),
                    value(row.getPumpingCount()), value(row.getPumpingMl()), value(row.getPumpingMinutes())
            );
        }).toList();
        return new StatsResponse(result);
    }

    private int value(Integer value) { return value == null ? 0 : value; }

    private int secondsToMinutes(int seconds) { return seconds / 60; }

    @Transactional
    public BabySummary updateBaby(long babyId, long familyId, UpdateBabyRequest request) {
        assertBabyAccess(babyId, familyId);
        if (mapper.updateBaby(
                babyId, familyId, request.nickname().trim(), request.birthday(),
                request.gender(), request.birthWeightGrams()
        ) != 1) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "宝宝不存在");
        }
        publishChangedAfterCommit(babyId);
        return mapper.findBabySummary(babyId);
    }

    @Transactional
    public BabyEvent feed(long babyId, long familyId, long operatorId, int amountMl, LocalDateTime eventTime, String clientEventId) {
        assertBabyAccess(babyId, familyId);
        var event = insert(babyId, operatorId, clientEventId, "FEED", eventTime, null, amountMl, null);
        publishChangedAfterCommit(babyId);
        return event;
    }

    @Transactional
    public BabyEvent feeding(long babyId, long familyId, long operatorId, FeedingRequest request) {
        assertBabyAccess(babyId, familyId);
        String type = request.type().trim().toUpperCase();
        if (!FEEDING_TYPES.contains(type)) {
            throw badRequest("不支持的喂养类型");
        }

        var start = request.eventTime() == null ? LocalDateTime.now() : request.eventTime();
        Integer amount = null;
        LocalDateTime end = null;
        var data = new LinkedHashMap<String, Object>();
        data.put("schemaVersion", 1);

        switch (type) {
            case DIRECT_BREASTFEED -> {
                if (request.amountMl() != null || request.leftMl() != null || request.rightMl() != null
                        || request.durationSeconds() != null) {
                    throw badRequest("亲喂记录只能填写左右侧时长");
                }
                int leftSeconds = nonNegative(request.leftSeconds(), "左侧亲喂时长");
                int rightSeconds = nonNegative(request.rightSeconds(), "右侧亲喂时长");
                int totalSeconds = leftSeconds + rightSeconds;
                if (totalSeconds <= 0 || totalSeconds > 86_400) {
                    throw badRequest("亲喂总时长必须在 1 秒到 24 小时之间");
                }
                String lastSide = normalizedSide(request.lastSide());
                if (("LEFT".equals(lastSide) && leftSeconds == 0)
                        || ("RIGHT".equals(lastSide) && rightSeconds == 0)) {
                    throw badRequest("结束侧必须有亲喂时长");
                }
                data.put("leftSeconds", leftSeconds);
                data.put("rightSeconds", rightSeconds);
                data.put("lastSide", lastSide);
                if (request.segments() != null) {
                    data.put("segments", canonicalSegments(request.segments(), leftSeconds, rightSeconds, lastSide));
                }
                end = start.plusSeconds(totalSeconds);
            }
            case BOTTLE_BREAST_MILK, FORMULA_FEED -> {
                amount = requiredAmount(request.amountMl(), "实际喝奶量");
                rejectOtherFeedingFields(request, type);
            }
            case PUMPING -> {
                if (request.leftSeconds() != null || request.rightSeconds() != null
                        || request.lastSide() != null || request.segments() != null) {
                    throw badRequest("泵奶记录只能填写左右侧泵奶量和可选时长");
                }
                int leftMl = nonNegative(request.leftMl(), "左侧泵奶量");
                int rightMl = nonNegative(request.rightMl(), "右侧泵奶量");
                amount = leftMl + rightMl;
                if (amount < 1 || amount > 1000) {
                    throw badRequest("泵奶总量必须在 1 到 1000 ml 之间");
                }
                if (request.amountMl() != null && !request.amountMl().equals(amount)) {
                    throw badRequest("泵奶总量必须等于左右侧之和");
                }
                Integer durationSeconds = optionalDuration(request.durationSeconds(), "泵奶时长");
                data.put("leftMl", leftMl);
                data.put("rightMl", rightMl);
                data.put("durationSeconds", durationSeconds);
                if (durationSeconds != null) end = start.plusSeconds(durationSeconds);
            }
            default -> throw new IllegalStateException("Unexpected feeding type " + type);
        }

        var event = insert(babyId, operatorId, request.clientEventId(), type, start, end, amount, json(data));
        publishChangedAfterCommit(babyId);
        return event;
    }

    @Transactional
    public BabyEvent simple(long babyId, long familyId, long operatorId, String type, LocalDateTime eventTime,
                            String clientEventId, Map<String, Object> data) {
        assertBabyAccess(babyId, familyId);
        var normalized = type.toUpperCase();
        if (!List.of("POOP", "PEE").contains(normalized)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Only POOP or PEE is allowed");
        }
        var event = insert(babyId, operatorId, clientEventId, normalized, eventTime, null, null, json(data));
        publishChangedAfterCommit(babyId);
        return event;
    }

    @Transactional
    public BabyEvent startSleep(long babyId, long familyId, long operatorId, LocalDateTime eventTime, String clientEventId) {
        assertBabyAccess(babyId, familyId);
        mapper.lockBabyForSleep(babyId, familyId);
        var active = mapper.findActiveSleepForUpdate(babyId);
        if (active != null) return active;
        var event = insert(babyId, operatorId, clientEventId, "SLEEP", eventTime, null, null, null);
        publishChangedAfterCommit(babyId);
        return event;
    }

    @Transactional
    public BabyEvent endSleepByClientEventId(long babyId, long familyId, long operatorId,
                                              String clientEventId, LocalDateTime eventTime) {
        assertBabyAccess(babyId, familyId);
        var event = mapper.findByClientEventId(babyId, clientEventId);
        if (event == null || !"SLEEP".equals(event.getEventType())) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Sleep event not found");
        }
        if (event.getEndTime() == null) {
            var end = eventTime == null ? LocalDateTime.now() : eventTime;
            if (end.isBefore(event.getStartTime())) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "醒来时间不能早于入睡时间");
            }
            mapper.endSleep(babyId, event.getId(), end, operatorId);
            event = mapper.findById(babyId, event.getId());
            publishChangedAfterCommit(babyId);
        }
        return event;
    }

    @Transactional
    public BabyEvent endSleep(long babyId, long familyId, long operatorId,
                              long sleepEventId, LocalDateTime eventTime) {
        assertBabyAccess(babyId, familyId);
        var before = mapper.findById(babyId, sleepEventId);
        if (before == null || !"SLEEP".equals(before.getEventType())) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Sleep event not found");
        }
        var end = eventTime == null ? LocalDateTime.now() : eventTime;
        if (end.isBefore(before.getStartTime())) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "醒来时间不能早于入睡时间");
        }
        if (mapper.endSleep(babyId, sleepEventId, end, operatorId) != 1) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Active sleep event not found");
        }
        var event = mapper.findById(babyId, sleepEventId);
        publishChangedAfterCommit(babyId);
        return event;
    }

    @Transactional
    public BabyEvent updateEvent(long babyId, long familyId, long eventId, UpdateEventRequest request) {
        assertBabyAccess(babyId, familyId);
        var event = mapper.findById(babyId, eventId);
        if (event == null) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "记录不存在");

        var start = request.eventTime() != null ? request.eventTime() : event.getStartTime();
        var end = request.endTime() != null ? request.endTime() : event.getEndTime();
        var amount = request.amountMl() != null ? request.amountMl() : event.getAmountMl();
        var eventData = request.data() != null ? json(request.data()) : event.getEventData();

        validateEventUpdate(event.getEventType(), amount, eventData);
        end = derivedFeedingEnd(event.getEventType(), start, end, eventData);
        if ("SLEEP".equals(event.getEventType()) && end != null && end.isBefore(start)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "醒来时间不能早于入睡时间");
        }

        if (mapper.updateEvent(babyId, eventId, start, end, amount, eventData, request.expectedUpdatedAt()) != 1) {
            if (mapper.findById(babyId, eventId) != null && request.expectedUpdatedAt() != null) {
                throw new ResponseStatusException(HttpStatus.CONFLICT, "记录已被其他设备修改");
            }
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "记录不存在");
        }
        publishChangedAfterCommit(babyId);
        return mapper.findById(babyId, eventId);
    }

    @Transactional
    public void deleteEvent(long babyId, long familyId, long eventId, LocalDateTime expectedUpdatedAt) {
        assertBabyAccess(babyId, familyId);
        var before = mapper.findById(babyId, eventId);
        if (before == null) return;
        if (mapper.deleteEvent(babyId, eventId, expectedUpdatedAt) != 1) {
            if (expectedUpdatedAt != null) {
                throw new ResponseStatusException(HttpStatus.CONFLICT, "记录已被其他设备修改");
            }
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "记录不存在");
        }
        publishChangedAfterCommit(babyId);
    }

    private BabyEvent insert(long babyId, long operatorId, String clientEventId, String type, LocalDateTime start,
                             LocalDateTime end, Integer amount, String eventData) {
        var event = new BabyEvent();
        event.setBabyId(babyId);
        event.setOperatorId(operatorId);
        event.setClientEventId(clientEventId);
        event.setEventType(type);
        event.setStartTime(start == null ? LocalDateTime.now() : start);
        event.setEndTime(end);
        event.setAmountMl(amount);
        event.setEventData(eventData);
        mapper.insert(event);
        BabyEvent stored = null;
        if (event.getId() != null) {
            stored = mapper.findById(babyId, event.getId());
        } else if (clientEventId != null && !clientEventId.isBlank()) {
            stored = mapper.findByClientEventId(babyId, clientEventId);
        }
        if (stored == null) {
            throw new ResponseStatusException(
                    HttpStatus.INTERNAL_SERVER_ERROR,
                    "记录已写入，但服务端无法读取写入结果"
            );
        }
        return stored;
    }

    private void publishChangedAfterCommit(long babyId) {
        if (!TransactionSynchronizationManager.isActualTransactionActive()
                || !TransactionSynchronizationManager.isSynchronizationActive()) {
            realtimeHub.publishChanged(babyId);
            return;
        }
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                realtimeHub.publishChanged(babyId);
            }
        });
    }

    private String json(Map<String, Object> data) {
        if (data == null || data.isEmpty()) return null;
        try {
            return objectMapper.writeValueAsString(data);
        } catch (JacksonException e) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "记录详情格式无效");
        }
    }

    @SuppressWarnings("unchecked")
    private void validateEventUpdate(String eventType, Integer amount, String eventData) {
        if ("FEED".equals(eventType) || BOTTLE_BREAST_MILK.equals(eventType) || FORMULA_FEED.equals(eventType)) {
            requiredAmount(amount, "实际喝奶量");
            return;
        }
        if (!DIRECT_BREASTFEED.equals(eventType) && !PUMPING.equals(eventType)) return;

        Map<String, Object> data;
        try {
            data = objectMapper.readValue(eventData, Map.class);
        } catch (JacksonException | IllegalArgumentException e) {
            throw badRequest("喂养记录详情格式无效");
        }
        if (DIRECT_BREASTFEED.equals(eventType)) {
            if (amount != null) throw badRequest("亲喂记录不能填写毫升数");
            int left = mapNonNegative(data, "leftSeconds", "左侧亲喂时长");
            int right = mapNonNegative(data, "rightSeconds", "右侧亲喂时长");
            if (left + right <= 0 || left + right > 86_400) throw badRequest("亲喂总时长必须在 1 秒到 24 小时之间");
            String side = normalizedSide(stringValue(data, "lastSide"));
            if (("LEFT".equals(side) && left == 0) || ("RIGHT".equals(side) && right == 0)) {
                throw badRequest("结束侧必须有亲喂时长");
            }
            requireSchemaVersion(data);
            validateStoredSegments(data, left, right, side);
            return;
        }

        int left = mapNonNegative(data, "leftMl", "左侧泵奶量");
        int right = mapNonNegative(data, "rightMl", "右侧泵奶量");
        if (amount == null || left + right != amount || amount < 1 || amount > 1000) {
            throw badRequest("泵奶总量必须等于左右侧之和，且在 1 到 1000 ml 之间");
        }
        optionalDuration(mapInteger(data, "durationSeconds"), "泵奶时长");
        requireSchemaVersion(data);
    }

    private void requireSchemaVersion(Map<String, Object> data) {
        if (!Integer.valueOf(1).equals(mapInteger(data, "schemaVersion"))) {
            throw badRequest("不支持的喂养详情版本");
        }
    }

    private int mapNonNegative(Map<String, Object> data, String key, String label) {
        return nonNegative(mapInteger(data, key), label);
    }

    private Integer mapInteger(Map<String, Object> data, String key) {
        Object value = data.get(key);
        if (!(value instanceof Number number)) return null;
        int integer = number.intValue();
        return number.doubleValue() == integer ? integer : null;
    }

    private String stringValue(Map<String, Object> data, String key) {
        Object value = data.get(key);
        return value instanceof String string ? string : null;
    }

    private int nonNegative(Integer value, String label) {
        if (value == null || value < 0) throw badRequest(label + "不能小于 0");
        return value;
    }

    private int positiveDuration(Integer value, String label) {
        if (value == null || value < 1 || value > 86_400) {
            throw badRequest(label + "必须在 1 秒到 24 小时之间");
        }
        return value;
    }

    private Integer optionalDuration(Integer value, String label) {
        return value == null ? null : positiveDuration(value, label);
    }

    private int requiredAmount(Integer amount, String label) {
        if (amount == null || amount < 1 || amount > 1000) {
            throw badRequest(label + "必须在 1 到 1000 ml 之间");
        }
        return amount;
    }

    private String normalizedSide(String side) {
        if (side == null) throw badRequest("必须填写亲喂结束侧");
        var normalized = side.trim().toUpperCase();
        if (!List.of("LEFT", "RIGHT").contains(normalized)) throw badRequest("亲喂结束侧只能是 LEFT 或 RIGHT");
        return normalized;
    }

    private void rejectOtherFeedingFields(FeedingRequest request, String type) {
        if (request.leftSeconds() != null || request.rightSeconds() != null || request.lastSide() != null
                || request.segments() != null
                || request.leftMl() != null || request.rightMl() != null || request.durationSeconds() != null) {
            throw badRequest(type + " 只能填写实际喝奶量");
        }
    }

    private ResponseStatusException badRequest(String message) {
        return new ResponseStatusException(HttpStatus.BAD_REQUEST, message);
    }

    private List<Map<String, Object>> canonicalSegments(List<FeedingRequest.FeedingSegment> segments,
                                                         int leftSeconds, int rightSeconds, String lastSide) {
        if (segments.isEmpty()) throw badRequest("亲喂片段不能为空");
        int leftTotal = 0;
        int rightTotal = 0;
        var canonical = new java.util.ArrayList<Map<String, Object>>(segments.size());
        String finalSide = null;
        for (var segment : segments) {
            if (segment == null) throw badRequest("亲喂片段格式无效");
            String side = normalizedSide(segment.side());
            int seconds = positiveSegmentSeconds(segment.seconds());
            if ("LEFT".equals(side)) leftTotal += seconds; else rightTotal += seconds;
            if (leftTotal + rightTotal > 86_400) throw badRequest("亲喂总时长不能超过 24 小时");
            var item = new LinkedHashMap<String, Object>();
            item.put("side", side);
            item.put("seconds", seconds);
            canonical.add(item);
            finalSide = side;
        }
        if (leftTotal != leftSeconds || rightTotal != rightSeconds) {
            throw badRequest("亲喂片段汇总必须等于左右侧时长");
        }
        if (!lastSide.equals(finalSide)) throw badRequest("亲喂结束侧必须与最后一个片段一致");
        return canonical;
    }

    private void validateStoredSegments(Map<String, Object> data, int leftSeconds, int rightSeconds, String lastSide) {
        if (!data.containsKey("segments") || data.get("segments") == null) return;
        if (!(data.get("segments") instanceof List<?> rawSegments) || rawSegments.isEmpty()) {
            throw badRequest("亲喂片段格式无效");
        }
        var segments = new java.util.ArrayList<FeedingRequest.FeedingSegment>(rawSegments.size());
        for (Object raw : rawSegments) {
            if (!(raw instanceof Map<?, ?> item)) throw badRequest("亲喂片段格式无效");
            Object side = item.get("side");
            Object seconds = item.get("seconds");
            Integer exactSeconds = null;
            if (seconds instanceof Number number) {
                int integer = number.intValue();
                if (number.doubleValue() == integer) exactSeconds = integer;
            }
            segments.add(new FeedingRequest.FeedingSegment(side instanceof String value ? value : null, exactSeconds));
        }
        canonicalSegments(segments, leftSeconds, rightSeconds, lastSide);
    }

    private int positiveSegmentSeconds(Integer seconds) {
        if (seconds == null || seconds < 1 || seconds > 86_400) {
            throw badRequest("每个亲喂片段必须在 1 秒到 24 小时之间");
        }
        return seconds;
    }

    @SuppressWarnings("unchecked")
    private LocalDateTime derivedFeedingEnd(String eventType, LocalDateTime start, LocalDateTime currentEnd, String eventData) {
        if (!DIRECT_BREASTFEED.equals(eventType) && !PUMPING.equals(eventType)) return currentEnd;
        Map<String, Object> data;
        try {
            data = objectMapper.readValue(eventData, Map.class);
        } catch (JacksonException | IllegalArgumentException e) {
            throw badRequest("喂养记录详情格式无效");
        }
        if (DIRECT_BREASTFEED.equals(eventType)) {
            return start.plusSeconds(mapInteger(data, "leftSeconds") + mapInteger(data, "rightSeconds"));
        }
        Integer duration = mapInteger(data, "durationSeconds");
        return duration == null ? null : start.plusSeconds(duration);
    }
}
