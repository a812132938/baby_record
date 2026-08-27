package com.babyrecord.config;

import org.junit.jupiter.api.Test;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.SynchronousQueue;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class AiConfigurationTest {

    @Test
    void executorRunsTwoRequestsAndRejectsQueueingAThird() throws Exception {
        var executor = new AiConfiguration().aiExecutor();
        var started = new CountDownLatch(2);
        var release = new CountDownLatch(1);
        Runnable blockingTask = () -> {
            started.countDown();
            try {
                release.await();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        };

        try {
            executor.execute(blockingTask);
            executor.execute(blockingTask);
            assertThat(started.await(1, TimeUnit.SECONDS)).isTrue();

            assertThat(executor.getCorePoolSize()).isEqualTo(2);
            assertThat(executor.getMaximumPoolSize()).isEqualTo(2);
            assertThat(executor.getQueue()).isInstanceOf(SynchronousQueue.class);
            assertThatThrownBy(() -> executor.execute(() -> {}))
                    .isInstanceOf(RejectedExecutionException.class);
        } finally {
            release.countDown();
            executor.shutdownNow();
        }
    }
}
