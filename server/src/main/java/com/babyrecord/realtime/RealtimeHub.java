package com.babyrecord.realtime;

import jakarta.annotation.PreDestroy;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

@Component
public class RealtimeHub {
    private final Map<Long, CopyOnWriteArrayList<SseEmitter>> emitters = new ConcurrentHashMap<>();

    public SseEmitter subscribe(long babyId) {
        var emitter = new SseEmitter(0L);
        emitters.compute(babyId, (ignored, existing) -> {
            var list = existing == null ? new CopyOnWriteArrayList<SseEmitter>() : existing;
            list.add(emitter);
            return list;
        });
        emitter.onCompletion(() -> remove(babyId, emitter));
        emitter.onTimeout(() -> remove(babyId, emitter));
        emitter.onError(error -> remove(babyId, emitter));
        send(babyId, emitter, SseEmitter.event()
                .name("connected")
                .data(Map.of("babyId", babyId, "at", Instant.now().toString())));
        return emitter;
    }

    public void publishChanged(long babyId) {
        var list = emitters.get(babyId);
        if (list == null) return;
        for (var emitter : list) {
            send(babyId, emitter, SseEmitter.event()
                    .name("changed")
                    .data(Map.of("babyId", babyId, "at", Instant.now().toString())));
        }
    }

    @Scheduled(
            fixedDelayString = "${app.realtime.heartbeat-interval-ms:20000}",
            initialDelayString = "${app.realtime.heartbeat-interval-ms:20000}"
    )
    void sendHeartbeats() {
        for (var entry : emitters.entrySet()) {
            for (var emitter : entry.getValue()) {
                send(entry.getKey(), emitter, SseEmitter.event().comment("heartbeat"));
            }
        }
    }

    @PreDestroy
    void close() {
        for (var list : emitters.values()) {
            for (var emitter : list) {
                try {
                    emitter.complete();
                } catch (IllegalStateException ignored) {
                    // The connection was already completed concurrently.
                }
            }
        }
        emitters.clear();
    }

    private void send(long babyId, SseEmitter emitter, SseEmitter.SseEventBuilder event) {
        try {
            emitter.send(event);
        } catch (IOException | IllegalStateException e) {
            remove(babyId, emitter);
        }
    }

    private void remove(long babyId, SseEmitter emitter) {
        emitters.computeIfPresent(babyId, (ignored, list) -> {
            list.remove(emitter);
            return list.isEmpty() ? null : list;
        });
    }
}
