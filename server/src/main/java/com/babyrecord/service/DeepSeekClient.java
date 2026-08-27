package com.babyrecord.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;

import java.io.BufferedReader;
import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

@Component
public class DeepSeekClient {
    public enum SearchPolicy {
        NONE,
        AUTO,
        REQUIRED
    }

    public enum ResponseProfile {
        DEFAULT(4096, Integer.MAX_VALUE),
        INITIAL_ANALYSIS(640, 260),
        FOLLOW_UP(960, 420);

        private final int maxOutputTokens;
        private final int maxHanCharacters;

        ResponseProfile(int maxOutputTokens, int maxHanCharacters) {
            this.maxOutputTokens = maxOutputTokens;
            this.maxHanCharacters = maxHanCharacters;
        }
    }

    public record CompletionResult(String content, boolean searchUsed) {}

    public record PromptMessage(String role, String content) {
        public PromptMessage {
            if (!"user".equals(role) && !"assistant".equals(role)) {
                throw new IllegalArgumentException("history role must be user or assistant");
            }
            if (content == null || content.isBlank()) {
                throw new IllegalArgumentException("history content must not be blank");
            }
        }
    }

    private static final Logger LOGGER = LoggerFactory.getLogger(DeepSeekClient.class);
    private static final int MAX_RESPONSE_BYTES = 1_000_000;
    private static final int MAX_CONTENT_CHARS = 24_000;
    private static final String INPUT_SCOPE_GUARD = """

            只能分析本次输入明确提供的信息。不要列举、命名、推断或建议输入未出现的记录字段；
            信息不足时只能概括为“记录覆盖有限”或“当前数据不足”。
            """;
    private static final String STRICT_RETRY_SUFFIX = """

            输出要求重申：不得补充输入未出现的记录字段或虚构数据；信息不足时明确说明限制。
            """;

    private final HttpClient httpClient;
    private final ObjectMapper objectMapper;
    private final boolean enabled;
    private final String apiKey;
    private final String baseUrl;
    private final String model;
    private final Duration attemptTimeout;
    private final Duration totalTimeout;

    @Autowired
    public DeepSeekClient(HttpClient aiHttpClient,
                          ObjectMapper objectMapper,
                          @Value("${app.ai.enabled:false}") boolean enabled,
                          @Value("${app.ai.deepseek.api-key:}") String apiKey,
                          @Value("${app.ai.deepseek.base-url:https://api.deepseek.com}") String baseUrl,
                          @Value("${app.ai.deepseek.model:deepseek-v4-flash}") String model,
                          @Value("${app.ai.deepseek.attempt-timeout-seconds:90}") long attemptTimeoutSeconds,
                          @Value("${app.ai.deepseek.total-timeout-seconds:90}") long totalTimeoutSeconds) {
        this(aiHttpClient, objectMapper, enabled, apiKey, baseUrl, model,
                Duration.ofSeconds(attemptTimeoutSeconds), Duration.ofSeconds(totalTimeoutSeconds));
    }

    DeepSeekClient(HttpClient aiHttpClient,
                   ObjectMapper objectMapper,
                   String apiKey,
                   String baseUrl,
                   String model,
                   Duration attemptTimeout,
                   Duration totalTimeout) {
        this(aiHttpClient, objectMapper, true, apiKey, baseUrl, model, attemptTimeout, totalTimeout);
    }

    DeepSeekClient(HttpClient aiHttpClient,
                   ObjectMapper objectMapper,
                   boolean enabled,
                   String apiKey,
                   String baseUrl,
                   String model,
                   Duration attemptTimeout,
                   Duration totalTimeout) {
        this.httpClient = aiHttpClient;
        this.objectMapper = objectMapper;
        this.enabled = enabled;
        this.apiKey = apiKey == null ? "" : apiKey.trim();
        this.baseUrl = normalizeBaseUrl(baseUrl);
        this.model = model == null || model.isBlank() ? "deepseek-v4-flash" : model.trim();
        this.attemptTimeout = requirePositive(attemptTimeout, "attemptTimeout");
        this.totalTimeout = requirePositive(totalTimeout, "totalTimeout");
        if (totalTimeout.compareTo(attemptTimeout) < 0) {
            throw new IllegalArgumentException("totalTimeout must be greater than or equal to attemptTimeout");
        }
        if (totalTimeout.compareTo(Duration.ofSeconds(120)) > 0) {
            throw new IllegalArgumentException("totalTimeout must not exceed 120 seconds");
        }
    }

    public String model() {
        return model;
    }

    public void ensureConfigured() {
        if (!enabled || apiKey.isBlank()) throw new AiProviderException("AI_CONFIG_MISSING");
    }

    public String chat(String systemPrompt, String userPrompt) {
        return streamChat(systemPrompt, userPrompt, ignored -> {});
    }

    public String streamChat(String systemPrompt, String userPrompt, Consumer<String> onDelta) {
        return streamChat(systemPrompt, userPrompt, SearchPolicy.NONE, onDelta).content();
    }

    public CompletionResult streamChat(String systemPrompt,
                                       String userPrompt,
                                       SearchPolicy searchPolicy,
                                       Consumer<String> onDelta) {
        return streamChat(systemPrompt, List.of(), userPrompt, searchPolicy, onDelta);
    }

    public CompletionResult streamChat(String systemPrompt,
                                       List<PromptMessage> history,
                                       String userPrompt,
                                       SearchPolicy searchPolicy,
                                       Consumer<String> onDelta) {
        return streamChat(systemPrompt, history, userPrompt, searchPolicy, ResponseProfile.DEFAULT, onDelta);
    }

    public CompletionResult streamChat(String systemPrompt,
                                       List<PromptMessage> history,
                                       String userPrompt,
                                       SearchPolicy searchPolicy,
                                       ResponseProfile responseProfile,
                                       Consumer<String> onDelta) {
        ensureConfigured();
        if (systemPrompt == null || systemPrompt.isBlank()) {
            throw new IllegalArgumentException("systemPrompt must not be blank");
        }
        if (userPrompt == null || userPrompt.isBlank()) {
            throw new IllegalArgumentException("userPrompt must not be blank");
        }
        if (history == null) throw new IllegalArgumentException("history must not be null");
        if (history.size() > 12) throw new IllegalArgumentException("history must not exceed 12 messages");
        if (history.stream().anyMatch(message -> message == null)) {
            throw new IllegalArgumentException("history must not contain null messages");
        }
        if (searchPolicy == null) throw new IllegalArgumentException("searchPolicy must not be null");
        if (responseProfile == null) throw new IllegalArgumentException("responseProfile must not be null");
        if (onDelta == null) throw new IllegalArgumentException("onDelta must not be null");

        long deadline = System.nanoTime() + totalTimeout.toNanos();
        boolean strictOutputRetry = false;
        for (int attempt = 0; attempt < 2; attempt++) {
            long remainingNanos = deadline - System.nanoTime();
            if (remainingNanos <= 0) throw new AiProviderException("AI_TIMEOUT");
            Duration requestTimeout = Duration.ofNanos(Math.min(remainingNanos, attemptTimeout.toNanos()));
            long requestDeadline = System.nanoTime() + requestTimeout.toNanos();
            String requestBody = requestBody(systemPrompt,
                    history,
                    strictOutputRetry ? userPrompt + STRICT_RETRY_SUFFIX : userPrompt,
                    searchPolicy,
                    responseProfile.maxOutputTokens);
            var request = HttpRequest.newBuilder(URI.create(baseUrl + "/responses"))
                    .timeout(requestTimeout)
                    .header("Authorization", "Bearer " + apiKey)
                    .header("Accept", "text/event-stream")
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(requestBody, StandardCharsets.UTF_8))
                    .build();
            var output = new SafeOutput(onDelta);
            try {
                var response = httpClient.send(request, HttpResponse.BodyHandlers.ofInputStream());
                int status = response.statusCode();
                if (status == 429 || status >= 500) {
                    closeQuietly(response.body());
                    if (attempt == 0) {
                        pauseBeforeRetry();
                        continue;
                    }
                    throw new AiProviderException(status == 429 ? "AI_RATE_LIMITED" : "AI_PROVIDER_UNAVAILABLE");
                }
                if (status < 200 || status >= 300) {
                    closeQuietly(response.body());
                    throw new AiProviderException("AI_PROVIDER_REJECTED");
                }
                long bodyNanos = requestDeadline - System.nanoTime();
                if (bodyNanos <= 0) {
                    closeQuietly(response.body());
                    throw new StreamTimeoutException();
                }
                CompletionResult result = readEventStream(response.body(), output, Duration.ofNanos(bodyNanos));
                if (result.content().isBlank()) throw new AiProviderException("AI_RESPONSE_INVALID");
                if (searchPolicy == SearchPolicy.REQUIRED && !result.searchUsed()) {
                    throw new AiProviderException("AI_SEARCH_UNAVAILABLE");
                }
                if (hanCharacterCount(result.content()) > responseProfile.maxHanCharacters) {
                    throw new AiProviderException("AI_RESPONSE_TOO_LONG");
                }
                return result;
            } catch (HttpTimeoutException | StreamTimeoutException e) {
                throw new AiProviderException("AI_TIMEOUT");
            } catch (ResponseTooLargeException e) {
                throw new AiProviderException("AI_RESPONSE_TOO_LARGE");
            } catch (IOException e) {
                if (attempt == 0 && !output.hasPublished()) {
                    pauseBeforeRetry();
                    continue;
                }
                throw new AiProviderException("AI_PROVIDER_UNAVAILABLE");
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new AiProviderException("AI_INTERRUPTED");
            } catch (AiProviderException e) {
                if (attempt == 0 && !output.hasPublished() && "AI_RESPONSE_INVALID".equals(e.errorCode())) {
                    strictOutputRetry = true;
                    pauseBeforeRetry();
                    continue;
                }
                throw e;
            }
        }
        throw new AiProviderException("AI_PROVIDER_UNAVAILABLE");
    }

    private CompletionResult readEventStream(InputStream responseBody,
                                             SafeOutput output,
                                             Duration timeout) throws IOException {
        boolean completed = false;
        var frame = new StringBuilder();
        var reading = new AtomicBoolean(true);
        var timedOut = new AtomicBoolean(false);
        CompletableFuture.delayedExecutor(Math.max(1, timeout.toMillis()), TimeUnit.MILLISECONDS).execute(() -> {
            if (reading.compareAndSet(true, false)) {
                timedOut.set(true);
                closeQuietly(responseBody);
            }
        });
        try (var bounded = new BoundedInputStream(responseBody, MAX_RESPONSE_BYTES);
             var reader = new BufferedReader(new InputStreamReader(bounded, StandardCharsets.UTF_8))) {
            String line;
            while (!completed && (line = reader.readLine()) != null) {
                if (line.isEmpty()) {
                    if (!frame.isEmpty()) {
                        completed = consumeFrame(frame.toString(), output);
                        frame.setLength(0);
                    }
                } else if (line.startsWith("data:")) {
                    if (!frame.isEmpty()) frame.append('\n');
                    String data = line.substring(5);
                    frame.append(data.startsWith(" ") ? data.substring(1) : data);
                }
            }
            if (!completed && !frame.isEmpty()) completed = consumeFrame(frame.toString(), output);
        } catch (IOException e) {
            if (timedOut.get()) throw new StreamTimeoutException();
            throw e;
        } finally {
            reading.set(false);
        }
        if (timedOut.get()) throw new StreamTimeoutException();
        if (!completed) throw new AiProviderException("AI_RESPONSE_INVALID");
        return output.finish();
    }

    @SuppressWarnings("unchecked")
    private boolean consumeFrame(String data, SafeOutput output) {
        try {
            Map<String, Object> event = objectMapper.readValue(data, Map.class);
            Object rawType = event.get("type");
            if (!(rawType instanceof String type)) throw new AiProviderException("AI_RESPONSE_INVALID");

            return switch (type) {
                case "response.output_text.delta" -> {
                    Object delta = event.get("delta");
                    if (!(delta instanceof String text)) throw new AiProviderException("AI_RESPONSE_INVALID");
                    output.accept(text);
                    yield false;
                }
                case "response.completed" -> true;
                case "response.incomplete" -> throw new AiProviderException("AI_RESPONSE_INCOMPLETE");
                case "response.failed", "error" -> throw new AiProviderException("AI_PROVIDER_UNAVAILABLE");
                case "response.web_search_call.completed" -> {
                    output.markSearchUsed();
                    yield false;
                }
                case "response.web_search_call.in_progress",
                     "response.web_search_call.searching" -> false;
                case "response.created",
                     "response.in_progress",
                     "response.output_item.added",
                     "response.output_item.done",
                     "response.content_part.added",
                     "response.content_part.done",
                     "response.output_text.done" -> false;
                default -> {
                    LOGGER.debug("Ignoring unsupported DeepSeek Responses stream event: {}", type);
                    yield false;
                }
            };
        } catch (JacksonException | ClassCastException e) {
            throw new AiProviderException("AI_RESPONSE_INVALID");
        }
    }

    private String requestBody(String systemPrompt,
                               List<PromptMessage> history,
                               String userPrompt,
                               SearchPolicy searchPolicy,
                               int maxOutputTokens) {
        var body = new LinkedHashMap<String, Object>();
        body.put("model", model);
        body.put("temperature", 0.2);
        body.put("max_output_tokens", maxOutputTokens);
        body.put("stream", true);
        body.put("reasoning", Map.of("effort", "none"));
        var input = new ArrayList<Map<String, Object>>(history.size() + 2);
        input.add(inputMessage("system", systemPrompt + INPUT_SCOPE_GUARD));
        history.forEach(message -> input.add(inputMessage(message.role(), message.content())));
        input.add(inputMessage("user", userPrompt));
        body.put("input", input);
        if (searchPolicy != SearchPolicy.NONE) {
            body.put("tools", List.of(Map.of("type", "web_search")));
            body.put("tool_choice", "auto");
        }
        try {
            return objectMapper.writeValueAsString(body);
        } catch (JacksonException e) {
            throw new AiProviderException("AI_REQUEST_INVALID");
        }
    }

    private static Map<String, Object> inputMessage(String role, String text) {
        return Map.of(
                "role", role,
                "content", List.of(Map.of("type", "input_text", "text", text))
        );
    }

    private static long hanCharacterCount(String text) {
        return text.codePoints()
                .filter(codePoint -> Character.UnicodeScript.of(codePoint) == Character.UnicodeScript.HAN)
                .count();
    }

    private static Duration requirePositive(Duration value, String name) {
        if (value == null || value.isZero() || value.isNegative()) {
            throw new IllegalArgumentException(name + " must be positive");
        }
        return value;
    }

    private static String normalizeBaseUrl(String raw) {
        String value = raw == null || raw.isBlank() ? "https://api.deepseek.com" : raw.trim();
        while (value.endsWith("/")) value = value.substring(0, value.length() - 1);
        URI uri;
        try {
            uri = URI.create(value);
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("baseUrl must be an absolute HTTPS URL", e);
        }
        String host = uri.getHost();
        boolean loopback = "localhost".equalsIgnoreCase(host)
                || "127.0.0.1".equals(host)
                || "::1".equals(host);
        boolean safeScheme = "https".equalsIgnoreCase(uri.getScheme())
                || ("http".equalsIgnoreCase(uri.getScheme()) && loopback);
        if (!safeScheme || host == null || uri.getUserInfo() != null
                || uri.getQuery() != null || uri.getFragment() != null) {
            throw new IllegalArgumentException("baseUrl must use HTTPS; HTTP is allowed only for loopback tests");
        }
        return value;
    }

    private static void pauseBeforeRetry() {
        try {
            Thread.sleep(400);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new AiProviderException("AI_INTERRUPTED");
        }
    }

    private static void closeQuietly(InputStream input) {
        try {
            input.close();
        } catch (IOException ignored) {
            // The response is already being discarded.
        }
    }

    private static final class SafeOutput {
        private final Consumer<String> callback;
        private final StringBuilder published = new StringBuilder();
        private int receivedChars;
        private boolean searchUsed;

        private SafeOutput(Consumer<String> callback) {
            this.callback = callback;
        }

        private void accept(String text) {
            if (text.isEmpty()) return;
            receivedChars += text.length();
            if (receivedChars > MAX_CONTENT_CHARS) throw new AiProviderException("AI_RESPONSE_INVALID");
            published.append(text);
            callback.accept(text);
        }

        private void markSearchUsed() {
            searchUsed = true;
        }

        private CompletionResult finish() {
            return new CompletionResult(published.toString(), searchUsed);
        }

        private boolean hasPublished() {
            return !published.isEmpty();
        }
    }

    private static final class BoundedInputStream extends FilterInputStream {
        private final long limit;
        private long count;

        private BoundedInputStream(InputStream input, long limit) {
            super(input);
            this.limit = limit;
        }

        @Override
        public int read() throws IOException {
            int value = super.read();
            if (value >= 0) increment(1);
            return value;
        }

        @Override
        public int read(byte[] bytes, int offset, int length) throws IOException {
            int read = super.read(bytes, offset, length);
            if (read > 0) increment(read);
            return read;
        }

        private void increment(int amount) throws ResponseTooLargeException {
            count += amount;
            if (count > limit) throw new ResponseTooLargeException();
        }
    }

    private static final class ResponseTooLargeException extends IOException {}

    private static final class StreamTimeoutException extends IOException {}
}
