package com.babyrecord.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.net.http.HttpClient;
import java.time.Duration;
import java.util.concurrent.SynchronousQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

@Configuration
public class AiConfiguration {
    @Bean(destroyMethod = "shutdown")
    ThreadPoolExecutor aiExecutor() {
        return new ThreadPoolExecutor(
                2, 2, 60, TimeUnit.SECONDS,
                new SynchronousQueue<>(),
                runnable -> {
                    var thread = new Thread(runnable, "baby-ai-worker");
                    thread.setDaemon(true);
                    return thread;
                },
                new ThreadPoolExecutor.AbortPolicy()
        );
    }

    @Bean
    HttpClient aiHttpClient() {
        return HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(8))
                .version(HttpClient.Version.HTTP_2)
                .build();
    }
}
