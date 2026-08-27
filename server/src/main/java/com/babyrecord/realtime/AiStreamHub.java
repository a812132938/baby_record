package com.babyrecord.realtime;

import com.babyrecord.model.AiMessageRow;
import jakarta.annotation.PreDestroy;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Supplier;

@Component
public class AiStreamHub {
    private static final int MAX_STREAMS = 256;
    private static final int MAX_SUBSCRIBERS_PER_STREAM = 8;
    private static final long EMITTER_TIMEOUT_MILLIS = 150_000L;
    private static final Duration TERMINAL_TTL = Duration.ofMinutes(5);
    private final Map<Long, StreamState> streams = new ConcurrentHashMap<>();
    private final Object[] messageLocks = locks();

    public <T> T serialized(long messageId, Supplier<T> operation) {
        synchronized (messageLocks[Math.floorMod(messageId, messageLocks.length)]) {
            return operation.get();
        }
    }

    public boolean isTracked(long messageId) {
        var state = streams.get(messageId);
        return state != null && state.isWorkerOwned();
    }

    public synchronized boolean prepare(long messageId) {
        cleanupExpired();
        var existing = streams.get(messageId);
        if (existing != null && !existing.isTerminal()) {
            existing.claimWorkerOwnership();
            return true;
        }
        if (existing != null) streams.remove(messageId, existing);
        if (!reserveCapacity()) return false;
        streams.put(messageId, new StreamState(messageId, true));
        return true;
    }

    public synchronized SseEmitter subscribe(AiMessageRow message) {
        cleanupExpired();
        // The database row may have been read just before the worker committed and published its terminal event.
        StreamState existing = streams.get(message.id());
        if (existing != null) return existing.subscribe();
        var restored = new StreamState(message.id(), false);
        if ("COMPLETED".equals(message.status())) {
            restored.restoreCompleted(message.content(), message.snapshotId());
            return restored.subscribe();
        }
        if ("FAILED".equals(message.status())) {
            restored.restoreFailed(message.errorCode());
            return restored.subscribe();
        }
        if (!reserveCapacity()) return null;
        streams.put(message.id(), restored);
        return restored.subscribe();
    }

    public void started(long messageId) {
        state(messageId).started();
    }

    public void delta(long messageId, String text) {
        if (text == null || text.isEmpty()) return;
        state(messageId).delta(text);
    }

    public void completed(long messageId, String content, long snapshotId) {
        state(messageId).completed(content, snapshotId);
    }

    public void failed(long messageId, String errorCode) {
        var state = streams.get(messageId);
        if (state != null) state.failed(errorCode);
    }

    @Scheduled(fixedDelay = 60_000, initialDelay = 60_000)
    synchronized void cleanupExpired() {
        Instant terminalCutoff = Instant.now().minus(TERMINAL_TTL);
        streams.entrySet().removeIf(entry -> entry.getValue().expired(terminalCutoff));
    }

    @Scheduled(
            fixedDelayString = "${app.ai.stream.heartbeat-interval-ms:10000}",
            initialDelayString = "${app.ai.stream.heartbeat-interval-ms:10000}"
    )
    void sendHeartbeats() {
        streams.values().forEach(StreamState::heartbeat);
    }

    @PreDestroy
    void close() {
        streams.values().forEach(StreamState::close);
        streams.clear();
    }

    private StreamState state(long messageId) {
        StreamState result = streams.get(messageId);
        if (result == null) throw new IllegalStateException("AI stream was not prepared");
        return result;
    }

    private boolean reserveCapacity() {
        if (streams.size() < MAX_STREAMS) return true;
        streams.values().stream()
                .filter(StreamState::isTerminal)
                .min(Comparator.comparing(StreamState::touchedAt))
                .ifPresent(state -> {
                    if (streams.remove(state.messageId, state)) state.close();
                });
        return streams.size() < MAX_STREAMS;
    }

    private static final class StreamState {
        private final long messageId;
        private final StringBuilder content = new StringBuilder();
        private final CopyOnWriteArrayList<SseEmitter> emitters = new CopyOnWriteArrayList<>();
        private long sequence;
        private boolean started;
        private boolean workerOwned;
        private Terminal terminal;
        private Instant touchedAt = Instant.now();

        private StreamState(long messageId, boolean workerOwned) {
            this.messageId = messageId;
            this.workerOwned = workerOwned;
        }

        private synchronized void claimWorkerOwnership() {
            if (terminal == null) workerOwned = true;
        }

        private synchronized boolean isWorkerOwned() {
            return terminal == null && workerOwned;
        }

        private synchronized SseEmitter subscribe() {
            if (terminal == null && emitters.size() >= MAX_SUBSCRIBERS_PER_STREAM) return null;
            var emitter = new SseEmitter(EMITTER_TIMEOUT_MILLIS);
            emitter.onCompletion(() -> emitters.remove(emitter));
            emitter.onTimeout(() -> emitters.remove(emitter));
            emitter.onError(error -> emitters.remove(emitter));
            emitters.add(emitter);
            touchedAt = Instant.now();
            sendOne(emitter, SseEmitter.event().comment("connected"));
            if (started || terminal != null) sendOne(emitter, event("started", Map.of("messageId", messageId)));
            if (!content.isEmpty()) {
                sendOne(emitter, event("sync", Map.of(
                        "messageId", messageId,
                        "content", content.toString()
                )));
            }
            if (terminal != null) {
                sendTerminal(emitter, terminal);
                completeOne(emitter);
            }
            return emitter;
        }

        private synchronized void heartbeat() {
            if (terminal != null || emitters.isEmpty()) return;
            var event = SseEmitter.event().comment("heartbeat");
            for (var emitter : emitters) sendOne(emitter, event);
        }

        private synchronized void started() {
            if (started || terminal != null) return;
            started = true;
            touchedAt = Instant.now();
            broadcast(event("started", Map.of("messageId", messageId)));
        }

        private synchronized void delta(String text) {
            if (terminal != null) return;
            if (!started) started();
            content.append(text);
            touchedAt = Instant.now();
            broadcast(event("delta", Map.of("messageId", messageId, "text", text)));
        }

        private synchronized void completed(String finalContent, long snapshotId) {
            if (terminal != null) return;
            if (!content.toString().equals(finalContent)) {
                throw new IllegalStateException("Streamed AI content differs from persisted content");
            }
            terminal = new Terminal(true, snapshotId, null);
            touchedAt = Instant.now();
            var current = new ArrayList<>(emitters);
            current.forEach(emitter -> sendTerminal(emitter, terminal));
            current.forEach(this::completeOne);
        }

        private synchronized void failed(String errorCode) {
            if (terminal != null) return;
            terminal = new Terminal(false, null, errorCode == null ? "AI_INTERNAL_ERROR" : errorCode);
            touchedAt = Instant.now();
            var current = new ArrayList<>(emitters);
            current.forEach(emitter -> sendTerminal(emitter, terminal));
            current.forEach(this::completeOne);
        }

        private synchronized void restoreCompleted(String finalContent, Long snapshotId) {
            started = true;
            content.append(finalContent == null ? "" : finalContent);
            terminal = new Terminal(true, snapshotId, null);
            touchedAt = Instant.now();
        }

        private synchronized void restoreFailed(String errorCode) {
            started = true;
            terminal = new Terminal(false, null, errorCode == null ? "AI_INTERNAL_ERROR" : errorCode);
            touchedAt = Instant.now();
        }

        private void sendTerminal(SseEmitter emitter, Terminal value) {
            if (value.completed) {
                var data = new LinkedHashMap<String, Object>();
                data.put("messageId", messageId);
                data.put("content", content.toString());
                data.put("snapshotId", value.snapshotId);
                data.put("status", "COMPLETED");
                data.put("conversationStatus", "READY");
                sendOne(emitter, event("completed", data));
                return;
            }
            sendOne(emitter, event("failed", Map.of(
                    "messageId", messageId,
                    "errorCode", value.errorCode,
                    "retryable", retryable(value.errorCode),
                    "action", retryable(value.errorCode) ? "RETRY" : "START_NEW"
            )));
        }

        private SseEmitter.SseEventBuilder event(String name, Map<String, ?> data) {
            long eventSequence = ++sequence;
            var payload = new LinkedHashMap<String, Object>(data);
            payload.put("seq", eventSequence);
            return SseEmitter.event()
                    .id(messageId + ":" + eventSequence)
                    .name(name)
                    .data(payload);
        }

        private void broadcast(SseEmitter.SseEventBuilder event) {
            for (var emitter : emitters) sendOne(emitter, event);
        }

        private void sendOne(SseEmitter emitter, SseEmitter.SseEventBuilder event) {
            try {
                emitter.send(event);
            } catch (IOException | IllegalStateException e) {
                emitters.remove(emitter);
            }
        }

        private void completeOne(SseEmitter emitter) {
            emitters.remove(emitter);
            try {
                emitter.complete();
            } catch (IllegalStateException ignored) {
                // The client disconnected while the model kept generating.
            }
        }

        private synchronized boolean isTerminal() {
            return terminal != null;
        }

        private synchronized boolean expired(Instant terminalCutoff) {
            return terminal != null && touchedAt.isBefore(terminalCutoff);
        }

        private synchronized Instant touchedAt() {
            return touchedAt;
        }

        private synchronized void close() {
            emitters.forEach(this::completeOne);
        }
    }

    private static boolean retryable(String errorCode) {
        return switch (errorCode) {
            case "AI_CONFIG_MISSING", "AI_PROVIDER_REJECTED", "AI_REQUEST_INVALID", "AI_STATE_CONFLICT" -> false;
            default -> true;
        };
    }

    private static Object[] locks() {
        var result = new Object[64];
        for (int index = 0; index < result.length; index++) result[index] = new Object();
        return result;
    }

    private record Terminal(boolean completed, Long snapshotId, String errorCode) {}
}
