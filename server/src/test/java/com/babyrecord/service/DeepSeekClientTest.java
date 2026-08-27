package com.babyrecord.service;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import tools.jackson.databind.ObjectMapper;

import java.net.InetSocketAddress;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class DeepSeekClientTest {
    private HttpServer server;
    private ExecutorService serverExecutor;

    @AfterEach
    void stopServer() {
        if (server != null) server.stop(0);
        if (serverExecutor != null) serverExecutor.shutdownNow();
    }

    @Test
    void springSelectsTheProductionConstructorWhenTheComponentHasATestConstructor() {
        try (var context = new AnnotationConfigApplicationContext()) {
            context.registerBean(HttpClient.class, HttpClient::newHttpClient);
            context.registerBean(ObjectMapper.class, () -> new ObjectMapper());
            context.register(DeepSeekClient.class);
            context.refresh();

            assertThat(context.getBean(DeepSeekClient.class).model()).isEqualTo("deepseek-v4-flash");
        }
    }

    @Test
    void sendsResponsesRequestWithoutSearchByDefault() throws Exception {
        var request = new AtomicReference<CapturedRequest>();
        startServer(List.of(reply(200, validResponse("分析完成"))), new AtomicInteger(), request);

        assertThat(client().chat("system", "user")).isEqualTo("分析完成");
        assertThat(request.get().path()).isEqualTo("/responses");
        assertThat(request.get().body())
                .contains("\"model\":\"deepseek-v4-flash\"")
                .contains("\"max_output_tokens\":4096")
                .contains("\"stream\":true")
                .contains("\"reasoning\":{\"effort\":\"none\"}")
                .doesNotContain("\"thinking\"")
                .contains("\"type\":\"input_text\"")
                .doesNotContain("\"tools\"", "\"tool_choice\"");
    }

    @Test
    void configuresAutoAndRequiredWebSearchAndReportsActualUse() throws Exception {
        var requests = new AtomicInteger();
        var captured = new AtomicReference<CapturedRequest>();
        startServer(List.of(
                reply(200, validResponse("自动回答")),
                reply(200, searchedResponse("联网回答"))
        ), requests, captured);
        var client = client();

        var automatic = client.streamChat("system", "user", DeepSeekClient.SearchPolicy.AUTO, ignored -> {});
        assertThat(automatic.content()).isEqualTo("自动回答");
        assertThat(automatic.searchUsed()).isFalse();
        assertThat(captured.get().body())
                .contains("\"tools\":[{\"type\":\"web_search\"}]")
                .contains("\"tool_choice\":\"auto\"");

        var required = client.streamChat("system", "user", DeepSeekClient.SearchPolicy.REQUIRED, ignored -> {});
        assertThat(required.content()).isEqualTo("联网回答");
        assertThat(required.searchUsed()).isTrue();
        assertThat(captured.get().body())
                .contains("\"tools\":[{\"type\":\"web_search\"}]")
                .contains("\"tool_choice\":\"auto\"")
                .doesNotContain("\"tool_choice\":{\"type\":\"web_search\"}");
    }

    @Test
    void requiredSearchRejectsACompletedResponseThatDidNotUseSearch() throws Exception {
        startServer(List.of(reply(200, validResponse("未联网回答"))),
                new AtomicInteger(), new AtomicReference<>());

        assertThatThrownBy(() -> client().streamChat(
                "system", "user", DeepSeekClient.SearchPolicy.REQUIRED, ignored -> {}))
                .isInstanceOfSatisfying(AiProviderException.class,
                        error -> assertThat(error.errorCode()).isEqualTo("AI_SEARCH_UNAVAILABLE"));
    }

    @Test
    @SuppressWarnings("unchecked")
    void sendsRecentConversationAsNativeRoleMessages() throws Exception {
        var captured = new AtomicReference<CapturedRequest>();
        startServer(List.of(reply(200, validResponse("追问回答"))), new AtomicInteger(), captured);

        var result = client().streamChat(
                "system",
                List.of(
                        new DeepSeekClient.PromptMessage("user", "上一问"),
                        new DeepSeekClient.PromptMessage("assistant", "上一答")
                ),
                "当前问题",
                DeepSeekClient.SearchPolicy.AUTO,
                ignored -> {}
        );

        assertThat(result.content()).isEqualTo("追问回答");
        var body = new ObjectMapper().readValue(captured.get().body(), java.util.Map.class);
        var input = (List<java.util.Map<String, Object>>) body.get("input");
        assertThat(input.stream().map(message -> message.get("role")).toList())
                .containsExactly("system", "user", "assistant", "user");
        assertThat(input.stream().map(message -> {
            var content = (List<java.util.Map<String, Object>>) message.get("content");
            return content.getFirst().get("text");
        }).toList()).containsExactly(
                "system\n只能分析本次输入明确提供的信息。不要列举、命名、推断或建议输入未出现的记录字段；\n"
                        + "信息不足时只能概括为“记录覆盖有限”或“当前数据不足”。\n",
                "上一问", "上一答", "当前问题"
        );
    }

    @Test
    void streamsUtf8DeltasUntilCompletedWithoutDoneSentinel() throws Exception {
        startServer(List.of(reply(200, streamResponse("第一句。", "第二句。"))),
                new AtomicInteger(), new AtomicReference<>());
        var deltas = new ArrayList<String>();

        String answer = client().streamChat("system", "user", deltas::add);

        assertThat(deltas).containsExactly("第一句。", "第二句。");
        assertThat(String.join("", deltas)).isEqualTo(answer).isEqualTo("第一句。第二句。");
    }

    @Test
    void appliesControlledTokenBudgetsToInitialAndFollowUpAnswers() throws Exception {
        var captured = new AtomicReference<CapturedRequest>();
        startServer(List.of(reply(200, validResponse("简短首答"))), new AtomicInteger(), captured);

        client().streamChat("system", List.of(), "user", DeepSeekClient.SearchPolicy.NONE,
                DeepSeekClient.ResponseProfile.INITIAL_ANALYSIS, ignored -> {});

        assertThat(captured.get().body()).contains("\"max_output_tokens\":640");

        stopServer();
        server = null;
        captured.set(null);
        startServer(List.of(reply(200, validResponse("简短追问回答"))), new AtomicInteger(), captured);

        client().streamChat("system", List.of(), "user", DeepSeekClient.SearchPolicy.NONE,
                DeepSeekClient.ResponseProfile.FOLLOW_UP, ignored -> {});

        assertThat(captured.get().body()).contains("\"max_output_tokens\":960");
    }

    @Test
    void rejectsAnOverlongAnswerAfterForwardingNativeStreamingDeltas() throws Exception {
        var requests = new AtomicInteger();
        startServer(List.of(reply(200, streamResponse("中".repeat(150), "文".repeat(111)))),
                requests, new AtomicReference<>());
        var deltas = new ArrayList<String>();

        assertThatThrownBy(() -> client().streamChat(
                "system", List.of(), "user", DeepSeekClient.SearchPolicy.NONE,
                DeepSeekClient.ResponseProfile.INITIAL_ANALYSIS, deltas::add))
                .isInstanceOfSatisfying(AiProviderException.class,
                        error -> assertThat(error.errorCode()).isEqualTo("AI_RESPONSE_TOO_LONG"));

        assertThat(deltas).containsExactly("中".repeat(150), "文".repeat(111));
        assertThat(requests).hasValue(1);
    }

    @Test
    void allowsCompleteHttpsSourcesOutsideTheChineseCharacterBudget() throws Exception {
        String answer = "中".repeat(260) + "\nSource: https://www.who.int/health-topics/infant-nutrition?lang=zh";
        startServer(List.of(reply(200, validResponse(answer))),
                new AtomicInteger(), new AtomicReference<>());
        var deltas = new ArrayList<String>();

        var result = client().streamChat(
                "system", List.of(), "user", DeepSeekClient.SearchPolicy.NONE,
                DeepSeekClient.ResponseProfile.INITIAL_ANALYSIS, deltas::add);

        assertThat(result.content()).isEqualTo(answer);
        assertThat(deltas).containsExactly(answer);
    }

    @Test
    void enforcesTheFollowUpChineseCharacterBudget() throws Exception {
        startServer(List.of(reply(200, validResponse("追".repeat(421)))),
                new AtomicInteger(), new AtomicReference<>());

        assertThatThrownBy(() -> client().streamChat(
                "system", List.of(), "user", DeepSeekClient.SearchPolicy.NONE,
                DeepSeekClient.ResponseProfile.FOLLOW_UP, ignored -> {}))
                .isInstanceOfSatisfying(AiProviderException.class,
                        error -> assertThat(error.errorCode()).isEqualTo("AI_RESPONSE_TOO_LONG"));
    }

    @Test
    void doesNotDeleteOutputSentencesContainingUrineTerms() throws Exception {
        startServer(List.of(reply(200, validResponse("若尿量明显减少，应联系医生。"))),
                new AtomicInteger(), new AtomicReference<>());

        assertThat(client().chat("system", "clean snapshot"))
                .isEqualTo("若尿量明显减少，应联系医生。");
    }

    @Test
    void retriesOnceWhenSuccessfulResponseHasNoAnswerContent() throws Exception {
        var requests = new AtomicInteger();
        var request = new AtomicReference<CapturedRequest>();
        startServer(List.of(reply(200, validResponse("")), reply(200, validResponse("重试成功"))), requests, request);

        assertThat(client().chat("system", "user")).isEqualTo("重试成功");
        assertThat(requests).hasValue(2);
        assertThat(request.get().body()).contains("不得补充输入未出现的记录字段");
    }

    @Test
    void retriesTransientHttpFailuresAndPreservesStableFinalError() throws Exception {
        var requests = new AtomicInteger();
        startServer(List.of(reply(503, "down"), reply(200, validResponse("恢复"))),
                requests, new AtomicReference<>());
        assertThat(client().chat("system", "user")).isEqualTo("恢复");
        assertThat(requests).hasValue(2);

        stopServer();
        server = null;
        requests.set(0);
        startServer(List.of(reply(429, "busy"), reply(429, "busy")), requests, new AtomicReference<>());
        assertThatThrownBy(() -> client().chat("system", "user"))
                .isInstanceOfSatisfying(AiProviderException.class,
                        error -> assertThat(error.errorCode()).isEqualTo("AI_RATE_LIMITED"));
        assertThat(requests).hasValue(2);
    }

    @Test
    void mapsFailedAndIncompleteTerminalEvents() throws Exception {
        startServer(List.of(reply(200, event("response.failed"))), new AtomicInteger(), new AtomicReference<>());
        assertThatThrownBy(() -> client().chat("system", "user"))
                .isInstanceOfSatisfying(AiProviderException.class,
                        error -> assertThat(error.errorCode()).isEqualTo("AI_PROVIDER_UNAVAILABLE"));

        stopServer();
        server = null;
        startServer(List.of(reply(200, event("response.incomplete"))),
                new AtomicInteger(), new AtomicReference<>());
        assertThatThrownBy(() -> client().chat("system", "user"))
                .isInstanceOfSatisfying(AiProviderException.class,
                        error -> assertThat(error.errorCode()).isEqualTo("AI_RESPONSE_INCOMPLETE"));
    }

    @Test
    void rejectsDisconnectAfterPublishedOutputWithoutRetrying() throws Exception {
        var requests = new AtomicInteger();
        startServer(List.of(reply(200, delta("尚未结束"))), requests, new AtomicReference<>());

        assertThatThrownBy(() -> client().chat("system", "user"))
                .isInstanceOfSatisfying(AiProviderException.class,
                        error -> assertThat(error.errorCode()).isEqualTo("AI_RESPONSE_INVALID"));
        assertThat(requests).hasValue(1);
    }

    @Test
    void retriesMalformedEventsBeforePublishingOutput() throws Exception {
        var requests = new AtomicInteger();
        startServer(List.of(reply(200, "data: not-json\n\n")), requests, new AtomicReference<>());

        assertThatThrownBy(() -> client().chat("system", "user"))
                .isInstanceOfSatisfying(AiProviderException.class,
                        error -> assertThat(error.errorCode()).isEqualTo("AI_RESPONSE_INVALID"));
        assertThat(requests).hasValue(2);
    }

    @Test
    void enforcesTheRawStreamingResponseByteLimit() throws Exception {
        startServer(List.of(reply(200, ":" + "x".repeat(1_000_001))),
                new AtomicInteger(), new AtomicReference<>());

        assertThatThrownBy(() -> client().chat("system", "user"))
                .isInstanceOfSatisfying(AiProviderException.class,
                        error -> assertThat(error.errorCode()).isEqualTo("AI_RESPONSE_TOO_LARGE"));
    }

    @Test
    void closesAStalledResponseBodyAtTheStreamingDeadlineAndReturnsTimeout() throws Exception {
        var requests = new AtomicInteger();
        startStallingServer(requests);

        assertThatThrownBy(() -> client(Duration.ofMillis(150), Duration.ofMillis(150))
                .chat("system", "user"))
                .isInstanceOfSatisfying(AiProviderException.class,
                        error -> assertThat(error.errorCode()).isEqualTo("AI_TIMEOUT"));
        assertThat(requests).hasValue(1);
    }

    @Test
    void rejectsInvalidArgumentsAndDeadlineConfiguration() {
        var client = client(Duration.ofMillis(1), Duration.ofMillis(1));
        assertThatThrownBy(() -> client.streamChat("system", "user", null, ignored -> {}))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("searchPolicy must not be null");
        assertThatThrownBy(() -> client.streamChat("system", "user", DeepSeekClient.SearchPolicy.AUTO, null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("onDelta must not be null");
        assertThatThrownBy(() -> client.streamChat("system", List.of(), "user",
                DeepSeekClient.SearchPolicy.NONE, null, ignored -> {}))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("responseProfile must not be null");
        assertThatThrownBy(() -> new DeepSeekClient.PromptMessage("system", "not allowed"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("history role must be user or assistant");
        assertThatThrownBy(() -> client.streamChat("system",
                java.util.Collections.nCopies(13, new DeepSeekClient.PromptMessage("user", "message")),
                "user", DeepSeekClient.SearchPolicy.NONE, ignored -> {}))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("history must not exceed 12 messages");
        assertThatThrownBy(() -> client(Duration.ZERO, Duration.ofMillis(1)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("attemptTimeout must be positive");
        assertThatThrownBy(() -> client(Duration.ofMillis(2), Duration.ofMillis(1)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("totalTimeout must be greater than or equal to attemptTimeout");
        assertThatThrownBy(() -> client(Duration.ofSeconds(1), Duration.ofSeconds(121)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("totalTimeout must not exceed 120 seconds");
        assertThatThrownBy(() -> new DeepSeekClient(
                HttpClient.newHttpClient(), new ObjectMapper(), false, "test-key",
                "https://api.deepseek.com", "deepseek-v4-flash",
                Duration.ofSeconds(1), Duration.ofSeconds(1)
        ).ensureConfigured())
                .isInstanceOfSatisfying(AiProviderException.class,
                        error -> assertThat(error.errorCode()).isEqualTo("AI_CONFIG_MISSING"));
        assertThatThrownBy(() -> new DeepSeekClient(
                HttpClient.newHttpClient(), new ObjectMapper(), "test-key",
                "http://ai-provider.example", "deepseek-v4-flash",
                Duration.ofSeconds(1), Duration.ofSeconds(1)
        ))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("baseUrl must use HTTPS");
    }

    private DeepSeekClient client() {
        return client(Duration.ofSeconds(28), Duration.ofSeconds(28));
    }

    private DeepSeekClient client(Duration attemptTimeout, Duration totalTimeout) {
        return new DeepSeekClient(HttpClient.newHttpClient(), new ObjectMapper(), "test-key",
                "http://127.0.0.1:" + (server == null ? 1 : server.getAddress().getPort()),
                "deepseek-v4-flash", attemptTimeout, totalTimeout);
    }

    private void startServer(List<Reply> replies,
                             AtomicInteger requests,
                             AtomicReference<CapturedRequest> captured) throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/responses", exchange -> {
            String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            captured.set(new CapturedRequest(exchange.getRequestURI().getPath(), body));
            int index = Math.min(requests.getAndIncrement(), replies.size() - 1);
            Reply reply = replies.get(index);
            byte[] responseBody = reply.body().getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "text/event-stream");
            exchange.sendResponseHeaders(reply.status(), reply.status() >= 400 ? responseBody.length : 0);
            for (int offset = 0; offset < responseBody.length; offset += 3) {
                exchange.getResponseBody().write(responseBody, offset, Math.min(3, responseBody.length - offset));
                exchange.getResponseBody().flush();
            }
            exchange.close();
        });
        server.start();
    }

    private void startStallingServer(AtomicInteger requests) throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        serverExecutor = Executors.newCachedThreadPool();
        server.setExecutor(serverExecutor);
        server.createContext("/responses", exchange -> {
            requests.incrementAndGet();
            exchange.getRequestBody().readAllBytes();
            exchange.getResponseHeaders().set("Content-Type", "text/event-stream");
            exchange.sendResponseHeaders(200, 0);
            exchange.getResponseBody().write("data: ".getBytes(StandardCharsets.UTF_8));
            exchange.getResponseBody().flush();
            try {
                Thread.sleep(2_000);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            } finally {
                exchange.close();
            }
        });
        server.start();
    }

    private static Reply reply(int status, String body) {
        return new Reply(status, body);
    }

    private static String validResponse(String content) {
        return streamResponse(content);
    }

    private static String searchedResponse(String content) {
        return event("response.web_search_call.in_progress")
                + event("response.web_search_call.searching")
                + event("response.web_search_call.completed")
                + streamResponse(content);
    }

    private static String streamResponse(String... chunks) {
        var response = new StringBuilder();
        for (String chunk : chunks) response.append(delta(chunk));
        return response.append(event("response.completed")).toString();
    }

    private static String delta(String content) {
        String escaped = content.replace("\\", "\\\\").replace("\"", "\\\"")
                .replace("\r", "\\r").replace("\n", "\\n");
        return "data: {\"type\":\"response.output_text.delta\",\"delta\":\"" + escaped + "\"}\n\n";
    }

    private static String event(String type) {
        return "data: {\"type\":\"" + type + "\"}\n\n";
    }

    private record Reply(int status, String body) {}

    private record CapturedRequest(String path, String body) {}
}
