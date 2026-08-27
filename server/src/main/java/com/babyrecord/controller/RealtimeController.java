package com.babyrecord.controller;

import com.babyrecord.auth.DeviceAuthInterceptor;
import com.babyrecord.auth.DeviceSessionPrincipal;
import com.babyrecord.realtime.RealtimeHub;
import com.babyrecord.service.BabyEventService;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

@RestController
@RequestMapping("/api/v1/babies/{babyId}")
public class RealtimeController {
    private final RealtimeHub realtimeHub;
    private final BabyEventService service;

    public RealtimeController(RealtimeHub realtimeHub, BabyEventService service) {
        this.realtimeHub = realtimeHub;
        this.service = service;
    }

    @GetMapping(value = "/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public ResponseEntity<SseEmitter> stream(
            @PathVariable long babyId,
            @RequestAttribute(DeviceAuthInterceptor.REQUEST_ATTRIBUTE) DeviceSessionPrincipal principal) {
        service.assertBabyAccess(babyId, principal.familyId());
        return ResponseEntity.ok()
                .header(HttpHeaders.CACHE_CONTROL, "no-cache, no-transform")
                .header("X-Accel-Buffering", "no")
                .contentType(MediaType.TEXT_EVENT_STREAM)
                .body(realtimeHub.subscribe(babyId));
    }
}
