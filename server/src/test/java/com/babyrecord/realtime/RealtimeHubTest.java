package com.babyrecord.realtime;

import com.babyrecord.BabyRecordApplication;
import com.babyrecord.auth.DeviceAuthInterceptor;
import com.babyrecord.auth.DeviceSessionPrincipal;
import com.babyrecord.controller.RealtimeController;
import com.babyrecord.service.BabyEventService;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.ScheduledAnnotationBeanPostProcessor;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.lang.reflect.Field;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.request;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class RealtimeHubTest {

    @Test
    void streamSendsConnectedHeartbeatAndChangedEventsWithStreamingHeaders() throws Exception {
        var hub = new RealtimeHub();
        var service = mock(BabyEventService.class);
        var principal = new DeviceSessionPrincipal(24L, 23L, 21L, "妈妈", "ADMIN");
        var mockMvc = MockMvcBuilders
                .standaloneSetup(new RealtimeController(hub, service))
                .build();

        var result = mockMvc.perform(get("/api/v1/babies/22/stream")
                        .requestAttr(DeviceAuthInterceptor.REQUEST_ATTRIBUTE, principal))
                .andExpect(status().isOk())
                .andExpect(request().asyncStarted())
                .andExpect(header().string("Content-Type", "text/event-stream"))
                .andExpect(header().string("Cache-Control", "no-cache, no-transform"))
                .andExpect(header().string("X-Accel-Buffering", "no"))
                .andReturn();

        hub.sendHeartbeats();
        hub.publishChanged(22L);

        assertThat(result.getResponse().getContentAsString())
                .contains("event:connected")
                .contains(":heartbeat")
                .contains("event:changed");
        verify(service).assertBabyAccess(22L, 21L);

        hub.close();
    }

    @Test
    void schedulingConfigurationRegistersTheHeartbeatTask() {
        try (var context = new AnnotationConfigApplicationContext()) {
            context.register(HeartbeatSchedulingTestConfiguration.class, RealtimeHub.class);
            context.refresh();

            var scheduler = context.getBean(ScheduledAnnotationBeanPostProcessor.class);
            assertThat(scheduler.getScheduledTasks()).hasSize(1);
            assertThat(BabyRecordApplication.class).hasAnnotation(EnableScheduling.class);
        }
    }

    @Test
    void failedHeartbeatRemovesTheEmitter() throws Exception {
        var hub = new RealtimeHub();
        var connections = connections(hub);
        connections.put(22L, new CopyOnWriteArrayList<>(java.util.List.of(new FailingEmitter())));

        hub.sendHeartbeats();

        assertThat(connections).doesNotContainKey(22L);
    }

    @Test
    void reconnectCannotBeDetachedByConcurrentCleanup() throws Exception {
        var hub = new RealtimeHub();
        var connections = connections(hub);
        var existing = new BlockingEmptyList(new FailingEmitter());
        connections.put(22L, existing);

        try (var executor = Executors.newFixedThreadPool(2)) {
            var cleanup = executor.submit(hub::sendHeartbeats);
            assertThat(existing.emptyResultCaptured.await(1, TimeUnit.SECONDS)).isTrue();

            var reconnectThread = new AtomicReference<Thread>();
            var reconnectStarted = new CountDownLatch(1);
            var reconnect = executor.submit(() -> {
                reconnectThread.set(Thread.currentThread());
                reconnectStarted.countDown();
                return hub.subscribe(22L);
            });
            try {
                assertThat(reconnectStarted.await(1, TimeUnit.SECONDS)).isTrue();
                assertThat(awaitDoneOrBlocked(reconnect, reconnectThread)).isTrue();
            } finally {
                existing.allowEmptyResult.countDown();
            }

            cleanup.get(1, TimeUnit.SECONDS);
            var newEmitter = reconnect.get(1, TimeUnit.SECONDS);
            assertThat(connections.get(22L)).containsExactly(newEmitter);
        } finally {
            existing.allowEmptyResult.countDown();
            hub.close();
        }
    }

    private static boolean awaitDoneOrBlocked(Future<?> task, AtomicReference<Thread> thread)
            throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(1);
        while (System.nanoTime() < deadline) {
            var runningThread = thread.get();
            if (task.isDone() || runningThread != null && runningThread.getState() == Thread.State.BLOCKED) {
                return true;
            }
            Thread.sleep(1);
        }
        return false;
    }

    @SuppressWarnings("unchecked")
    private static Map<Long, CopyOnWriteArrayList<SseEmitter>> connections(RealtimeHub hub)
            throws NoSuchFieldException, IllegalAccessException {
        Field field = RealtimeHub.class.getDeclaredField("emitters");
        field.setAccessible(true);
        return (Map<Long, CopyOnWriteArrayList<SseEmitter>>) field.get(hub);
    }

    private static final class FailingEmitter extends SseEmitter {
        @Override
        public void send(SseEventBuilder builder) throws IOException {
            throw new IOException("connection closed");
        }
    }

    private static final class BlockingEmptyList extends CopyOnWriteArrayList<SseEmitter> {
        private final CountDownLatch emptyResultCaptured = new CountDownLatch(1);
        private final CountDownLatch allowEmptyResult = new CountDownLatch(1);

        private BlockingEmptyList(SseEmitter emitter) {
            super(java.util.List.of(emitter));
        }

        @Override
        public boolean isEmpty() {
            boolean empty = super.isEmpty();
            if (!empty) return false;
            emptyResultCaptured.countDown();
            try {
                if (!allowEmptyResult.await(1, TimeUnit.SECONDS)) {
                    throw new IllegalStateException("Timed out coordinating concurrent cleanup");
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException("Interrupted coordinating concurrent cleanup", e);
            }
            return true;
        }
    }

    @EnableScheduling
    private static final class HeartbeatSchedulingTestConfiguration {
    }
}
