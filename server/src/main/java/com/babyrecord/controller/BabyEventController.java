package com.babyrecord.controller;

import com.babyrecord.auth.DeviceAuthInterceptor;
import com.babyrecord.auth.DeviceSessionPrincipal;
import com.babyrecord.dto.DashboardResponse;
import com.babyrecord.dto.FeedRequest;
import com.babyrecord.dto.FeedingRequest;
import com.babyrecord.dto.SimpleEventRequest;
import com.babyrecord.dto.StatsResponse;
import com.babyrecord.dto.UpdateEventRequest;
import com.babyrecord.model.BabyEvent;
import com.babyrecord.service.BabyEventService;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.Map;

@RestController
@RequestMapping("/api/v1/babies/{babyId}")
public class BabyEventController {
    private final BabyEventService service;

    public BabyEventController(BabyEventService service) {
        this.service = service;
    }

    @GetMapping("/dashboard")
    public DashboardResponse dashboard(@PathVariable long babyId,
                                       @RequestAttribute(DeviceAuthInterceptor.REQUEST_ATTRIBUTE) DeviceSessionPrincipal principal) {
        return service.dashboard(babyId, principal.familyId());
    }

    @GetMapping("/stats")
    public StatsResponse stats(@PathVariable long babyId,
                               @RequestParam(defaultValue = "7") int days,
                               @RequestAttribute(DeviceAuthInterceptor.REQUEST_ATTRIBUTE) DeviceSessionPrincipal principal) {
        return service.stats(babyId, principal.familyId(), days);
    }

    @GetMapping("/events")
    public java.util.List<BabyEvent> history(@PathVariable long babyId,
                                             @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date,
                                             @RequestAttribute(DeviceAuthInterceptor.REQUEST_ATTRIBUTE) DeviceSessionPrincipal principal) {
        return service.history(babyId, principal.familyId(), date);
    }

    @PostMapping("/events/feed")
    public BabyEvent feed(@PathVariable long babyId,
                          @RequestAttribute(DeviceAuthInterceptor.REQUEST_ATTRIBUTE) DeviceSessionPrincipal principal,
                          @Valid @RequestBody FeedRequest request) {
        return service.feed(babyId, principal.familyId(), principal.userId(), request.amountMl(), request.eventTime(), request.clientEventId());
    }

    @PostMapping("/events/feeding")
    public BabyEvent feeding(@PathVariable long babyId,
                             @RequestAttribute(DeviceAuthInterceptor.REQUEST_ATTRIBUTE) DeviceSessionPrincipal principal,
                             @Valid @RequestBody FeedingRequest request) {
        return service.feeding(babyId, principal.familyId(), principal.userId(), request);
    }

    @PostMapping("/events/simple")
    public BabyEvent simple(@PathVariable long babyId,
                            @RequestAttribute(DeviceAuthInterceptor.REQUEST_ATTRIBUTE) DeviceSessionPrincipal principal,
                            @Valid @RequestBody SimpleEventRequest request) {
        return service.simple(babyId, principal.familyId(), principal.userId(), request.type(), request.eventTime(), request.clientEventId(), request.data());
    }

    @PatchMapping("/events/{eventId}")
    public BabyEvent update(@PathVariable long babyId,
                            @PathVariable long eventId,
                            @RequestAttribute(DeviceAuthInterceptor.REQUEST_ATTRIBUTE) DeviceSessionPrincipal principal,
                            @Valid @RequestBody UpdateEventRequest request) {
        return service.updateEvent(babyId, principal.familyId(), eventId, request);
    }

    @DeleteMapping("/events/{eventId}")
    public ResponseEntity<Void> delete(@PathVariable long babyId,
                                       @PathVariable long eventId,
                                       @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) LocalDateTime expectedUpdatedAt,
                                       @RequestAttribute(DeviceAuthInterceptor.REQUEST_ATTRIBUTE) DeviceSessionPrincipal principal) {
        service.deleteEvent(babyId, principal.familyId(), eventId, expectedUpdatedAt);
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/sleep/start")
    public BabyEvent startSleep(@PathVariable long babyId,
                                @RequestAttribute(DeviceAuthInterceptor.REQUEST_ATTRIBUTE) DeviceSessionPrincipal principal,
                                @RequestBody(required = false) Map<String, String> body) {
        LocalDateTime time = body != null && body.get("eventTime") != null ? LocalDateTime.parse(body.get("eventTime")) : null;
        String clientEventId = body != null ? body.get("clientEventId") : null;
        return service.startSleep(babyId, principal.familyId(), principal.userId(), time, clientEventId);
    }

    @PostMapping("/sleep/end")
    public BabyEvent endSleepByClient(@PathVariable long babyId,
                                      @RequestAttribute(DeviceAuthInterceptor.REQUEST_ATTRIBUTE) DeviceSessionPrincipal principal,
                                      @RequestBody Map<String, String> body) {
        String clientEventId = body.get("clientEventId");
        if (clientEventId == null || clientEventId.isBlank()) {
            throw new IllegalArgumentException("clientEventId is required");
        }
        LocalDateTime time = body.get("eventTime") != null ? LocalDateTime.parse(body.get("eventTime")) : null;
        return service.endSleepByClientEventId(babyId, principal.familyId(), principal.userId(), clientEventId, time);
    }

    @PostMapping("/sleep/{eventId}/end")
    public BabyEvent endSleep(@PathVariable long babyId,
                              @PathVariable long eventId,
                              @RequestAttribute(DeviceAuthInterceptor.REQUEST_ATTRIBUTE) DeviceSessionPrincipal principal,
                              @RequestBody(required = false) Map<String, String> body) {
        LocalDateTime time = body != null && body.get("eventTime") != null ? LocalDateTime.parse(body.get("eventTime")) : null;
        return service.endSleep(babyId, principal.familyId(), principal.userId(), eventId, time);
    }
}
