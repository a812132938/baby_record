package com.babyrecord.realtime;

import com.babyrecord.model.AiMessageRow;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.http.MediaType;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.ScheduledAnnotationBeanPostProcessor;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.time.LocalDateTime;
import java.nio.charset.StandardCharsets;
import java.io.IOException;
import java.lang.reflect.Field;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.asyncDispatch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.request;

class AiStreamHubTest {
    private final AiStreamHub hub = new AiStreamHub();

    @AfterEach
    void closeHub() {
        hub.close();
    }

    @Test
    void lateSubscriberReceivesAccumulatedPrefixThenLiveDeltasAndTerminalResult() throws Exception {
        hub.prepare(41L);
        hub.started(41L);
        hub.delta(41L, "已经生成");
        var mvc = MockMvcBuilders.standaloneSetup(new StreamController(hub, pending())).build();

        var result = mvc.perform(get("/stream"))
                .andExpect(request().asyncStarted())
                .andReturn();
        hub.delta(41L, "完成");
        hub.completed(41L, "已经生成完成", 71L);
        mvc.perform(asyncDispatch(result));

        assertThat(result.getResponse().getContentAsString(StandardCharsets.UTF_8))
                .contains("event:started")
                .contains("event:sync")
                .contains("\"content\":\"已经生成\"")
                .contains("\"seq\":4")
                .contains("event:delta")
                .contains("\"text\":\"完成\"")
                .contains("event:completed")
                .contains("\"snapshotId\":71")
                .contains("\"content\":\"已经生成完成\"")
                .contains("id:41:3", "id:41:4", "id:41:5", "id:41:6");
    }

    @Test
    void capacityPressureNeverEvictsAQueuedOrGeneratingStream() {
        for (long messageId = 1; messageId <= 256; messageId++) {
            assertThat(hub.prepare(messageId)).isTrue();
        }
        hub.started(1L);
        hub.delta(1L, "完整");

        assertThat(hub.prepare(257L)).isFalse();
        hub.completed(1L, "完整", 71L);
    }

    @Test
    void cancelledQueuedStreamBecomesEvictableAndReleasesCapacity() {
        for (long messageId = 1; messageId <= 256; messageId++) {
            assertThat(hub.prepare(messageId)).isTrue();
        }

        hub.failed(1L, "AI_REQUEST_CANCELLED");

        assertThat(hub.prepare(257L)).isTrue();
    }

    @Test
    void subscriberCountIsBoundedPerPendingMessage() {
        hub.prepare(41L);
        for (int index = 0; index < 8; index++) {
            assertThat(hub.subscribe(pending())).isNotNull();
        }

        assertThat(hub.subscribe(pending())).isNull();
    }

    @Test
    void passivePendingSubscriptionIsUpgradedInPlaceWhenAWorkerClaimsIt() {
        assertThat(hub.subscribe(pending())).isNotNull();
        assertThat(hub.isTracked(41L)).isFalse();

        assertThat(hub.prepare(41L)).isTrue();

        assertThat(hub.isTracked(41L)).isTrue();
        for (int index = 1; index < 8; index++) {
            assertThat(hub.subscribe(pending())).isNotNull();
        }
        assertThat(hub.subscribe(pending())).isNull();
    }

    @Test
    void terminalDatabaseMessageCanBeReplayedAfterInMemoryStateIsAbsent() throws Exception {
        var mvc = MockMvcBuilders.standaloneSetup(new StreamController(hub, completed())).build();

        var result = mvc.perform(get("/stream"))
                .andExpect(request().asyncStarted())
                .andReturn();
        mvc.perform(asyncDispatch(result));

        assertThat(result.getResponse().getContentAsString(StandardCharsets.UTF_8))
                .contains("event:started", "event:sync", "event:completed")
                .contains("\"content\":\"数据库完整正文\"")
                .contains("\"conversationStatus\":\"READY\"");
    }

    @Test
    void stalePendingDatabaseReadCannotReplaceANewerInMemoryCompletion() throws Exception {
        hub.prepare(41L);
        hub.started(41L);
        hub.delta(41L, "内存中的完整正文");
        hub.completed(41L, "内存中的完整正文", 72L);
        var mvc = MockMvcBuilders.standaloneSetup(new StreamController(hub, pending())).build();

        var result = mvc.perform(get("/stream"))
                .andExpect(request().asyncStarted())
                .andReturn();
        mvc.perform(asyncDispatch(result));

        assertThat(result.getResponse().getContentAsString(StandardCharsets.UTF_8))
                .contains("event:sync", "event:completed")
                .contains("\"content\":\"内存中的完整正文\"")
                .contains("\"snapshotId\":72");
    }

    @Test
    void sendsConnectedAndHeartbeatCommentsWithoutConsumingBusinessSequence() throws Exception {
        hub.prepare(41L);
        var mvc = MockMvcBuilders.standaloneSetup(new StreamController(hub, pending())).build();
        var result = mvc.perform(get("/stream"))
                .andExpect(request().asyncStarted())
                .andReturn();

        hub.sendHeartbeats();
        hub.started(41L);
        hub.delta(41L, "流式内容");
        hub.completed(41L, "流式内容", 71L);
        mvc.perform(asyncDispatch(result));

        assertThat(result.getResponse().getContentAsString(StandardCharsets.UTF_8))
                .contains(":connected", ":heartbeat")
                .contains("event:started", "event:delta", "event:completed")
                .contains("\"seq\":1", "\"seq\":2", "\"seq\":3")
                .doesNotContain("event:connected", "event:heartbeat", "\"seq\":4");
    }

    @Test
    void failedHeartbeatRemovesDisconnectedSubscriber() throws Exception {
        hub.prepare(41L);
        hub.subscribe(pending());
        var emitters = emitters(hub, 41L);
        emitters.clear();
        emitters.add(new FailingEmitter());

        hub.sendHeartbeats();

        assertThat(emitters).isEmpty();
    }

    @Test
    void schedulingRegistersCleanupAndHeartbeatTasks() {
        try (var context = new AnnotationConfigApplicationContext()) {
            context.register(SchedulingTestConfiguration.class, AiStreamHub.class);
            context.refresh();

            assertThat(context.getBean(ScheduledAnnotationBeanPostProcessor.class).getScheduledTasks()).hasSize(2);
        }
    }

    private static AiMessageRow pending() {
        return message("PENDING", null, null, null);
    }

    private static AiMessageRow completed() {
        return message("COMPLETED", "数据库完整正文", 71L, null);
    }

    private static AiMessageRow message(String status, String content, Long snapshotId, String errorCode) {
        var now = LocalDateTime.now();
        return new AiMessageRow(41L, 31L, 21L, 11L, null, "request-id", "ASSISTANT", status,
                content, snapshotId, errorCode, false, null, now, now, now);
    }

    @SuppressWarnings("unchecked")
    private static CopyOnWriteArrayList<SseEmitter> emitters(AiStreamHub hub, long messageId) throws Exception {
        Field streamsField = AiStreamHub.class.getDeclaredField("streams");
        streamsField.setAccessible(true);
        var streams = (Map<Long, ?>) streamsField.get(hub);
        Object state = streams.get(messageId);
        Field emittersField = state.getClass().getDeclaredField("emitters");
        emittersField.setAccessible(true);
        return (CopyOnWriteArrayList<SseEmitter>) emittersField.get(state);
    }

    private static final class FailingEmitter extends SseEmitter {
        @Override
        public void send(SseEventBuilder builder) throws IOException {
            throw new IOException("connection closed");
        }
    }

    @EnableScheduling
    private static final class SchedulingTestConfiguration {
    }

    @RestController
    private static final class StreamController {
        private final AiStreamHub hub;
        private final AiMessageRow message;

        private StreamController(AiStreamHub hub, AiMessageRow message) {
            this.hub = hub;
            this.message = message;
        }

        @GetMapping(value = "/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
        SseEmitter stream() {
            return hub.subscribe(message);
        }
    }
}
