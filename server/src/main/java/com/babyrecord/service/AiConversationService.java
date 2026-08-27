package com.babyrecord.service;

import com.babyrecord.auth.DeviceSessionPrincipal;
import com.babyrecord.dto.AiConversationDetail;
import com.babyrecord.dto.AiConversationListResponse;
import com.babyrecord.dto.AiConversationSummary;
import com.babyrecord.dto.AiCreateConversationRequest;
import com.babyrecord.dto.AiMessageResponse;
import com.babyrecord.dto.AiQuestionRequest;
import com.babyrecord.dto.AiSnapshotResponse;
import com.babyrecord.mapper.AiConversationMapper;
import com.babyrecord.model.AiConversationRow;
import com.babyrecord.model.AiMessageRow;
import com.babyrecord.model.AiSnapshotRow;
import com.babyrecord.realtime.AiStreamHub;
import com.babyrecord.realtime.RealtimeHub;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;
import org.springframework.http.HttpStatus;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;

@Service
public class AiConversationService {
    private static final Logger LOGGER = LoggerFactory.getLogger(AiConversationService.class);
    private static final int USER_HOURLY_LIMIT = 12;
    private static final int FAMILY_DAILY_LIMIT = 60;
    private static final int CONVERSATION_QUESTION_LIMIT = 30;
    private static final int HISTORY_MESSAGE_LIMIT = 12;
    private static final DateTimeFormatter HISTORY_TIME = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm");
    private static final List<String> INTERPRETIVE_QUESTION_TERMS = List.of(
            "正常", "合理", "够不够", "是否够", "怎么办", "怎么做", "建议", "风险", "危险",
            "异常", "为什么", "原因", "需要", "应该", "可不可以", "能不能", "趋势", "变化",
            "分析", "评价", "判断", "如何", "怎么样", "指南", "标准", "推荐", "担心"
    );
    private static final List<String> RECORD_LOOKUP_TERMS = List.of(
            "记录", "几次", "多少", "几点", "什么时候", "最近一次", "上一次", "间隔", "时长",
            "多久", "毫升", "哪次", "列表", "数据", "汇总", "合计", "总共"
    );
    private static final List<String> OUT_OF_SCOPE_TERMS = List.of(
            "股票", "基金", "投资", "理财", "代码", "编程", "写程序", "游戏攻略", "彩票", "成人内容",
            "政治", "军事", "写论文", "写广告", "翻译合同"
    );
    private final AiConversationMapper mapper;
    private final BabyEventService babyEventService;
    private final AiSnapshotService snapshotService;
    private final AiPromptBuilder promptBuilder;
    private final DeepSeekClient deepSeekClient;
    private final AiStreamHub aiStreamHub;
    private final RealtimeHub realtimeHub;
    private final ObjectMapper objectMapper;
    private final TransactionTemplate transactionTemplate;
    private final ThreadPoolExecutor aiExecutor;

    public AiConversationService(AiConversationMapper mapper,
                                 BabyEventService babyEventService,
                                 AiSnapshotService snapshotService,
                                 AiPromptBuilder promptBuilder,
                                 DeepSeekClient deepSeekClient,
                                 AiStreamHub aiStreamHub,
                                 RealtimeHub realtimeHub,
                                 ObjectMapper objectMapper,
                                 TransactionTemplate transactionTemplate,
                                 ThreadPoolExecutor aiExecutor) {
        this.mapper = mapper;
        this.babyEventService = babyEventService;
        this.snapshotService = snapshotService;
        this.promptBuilder = promptBuilder;
        this.deepSeekClient = deepSeekClient;
        this.aiStreamHub = aiStreamHub;
        this.realtimeHub = realtimeHub;
        this.objectMapper = objectMapper;
        this.transactionTemplate = transactionTemplate;
        this.aiExecutor = aiExecutor;
    }

    public AiConversationListResponse list(long babyId, DeviceSessionPrincipal principal) {
        babyEventService.assertBabyAccess(babyId, principal.familyId());
        var items = mapper.listConversations(principal.familyId(), babyId).stream()
                .map(row -> new AiConversationSummary(
                        row.id(), row.title(), row.status(), row.model(), row.lastErrorCode(),
                        row.createdAt(), row.updatedAt(), snapshot(mapper.findLatestSnapshot(row.id(), principal.familyId(), babyId))
                ))
                .toList();
        return new AiConversationListResponse(items);
    }

    public AiConversationDetail create(long babyId,
                                       DeviceSessionPrincipal principal,
                                       AiCreateConversationRequest request) {
        babyEventService.assertBabyAccess(babyId, principal.familyId());
        if (!request.dataProcessingAccepted()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "必须同意将宝宝记录发送给 AI 服务处理");
        }
        ensureProviderConfigured();
        var existing = mapper.findByClientRequest(principal.familyId(), babyId, request.clientRequestId());
        if (existing != null) return detail(existing, principal.familyId(), babyId);
        checkRateLimits(principal.userId(), principal.familyId(), babyId, null);

        SubmitResult submitted = transactionTemplate.execute(status -> {
            var duplicate = mapper.findByClientRequest(principal.familyId(), babyId, request.clientRequestId());
            if (duplicate != null) return new SubmitResult(duplicate, null, false);
            mapper.insertConversation(principal.familyId(), babyId, principal.userId(), request.clientRequestId(),
                    deepSeekClient.model());
            var conversation = mapper.findByClientRequest(principal.familyId(), babyId, request.clientRequestId());
            if (conversation == null) throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, "AI 会话创建失败");
            mapper.insertAssistantPlaceholder(conversation.id(), principal.familyId(), babyId, request.clientRequestId());
            var assistant = mapper.findMessageByRequest(conversation.id(), principal.familyId(), babyId,
                    "ASSISTANT", request.clientRequestId());
            return new SubmitResult(conversation, assistant, true);
        });
        if (submitted == null) throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, "AI 会话创建失败");
        if (submitted.created()) enqueue(submitted.conversation(), submitted.assistant());
        realtimeHub.publishChanged(babyId);
        return get(babyId, submitted.conversation().id(), principal);
    }

    public AiConversationDetail get(long babyId, long conversationId, DeviceSessionPrincipal principal) {
        babyEventService.assertBabyAccess(babyId, principal.familyId());
        var conversation = requiredConversation(conversationId, principal.familyId(), babyId);
        return detail(conversation, principal.familyId(), babyId);
    }

    public AiSnapshotResponse getSnapshot(long babyId,
                                          long conversationId,
                                          long snapshotId,
                                          DeviceSessionPrincipal principal) {
        babyEventService.assertBabyAccess(babyId, principal.familyId());
        requiredConversation(conversationId, principal.familyId(), babyId);
        var result = mapper.findSnapshot(snapshotId, conversationId, principal.familyId(), babyId);
        if (result == null) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "分析数据快照不存在");
        return snapshot(result);
    }

    public SseEmitter stream(long babyId,
                             long conversationId,
                             long messageId,
                             DeviceSessionPrincipal principal) {
        babyEventService.assertBabyAccess(babyId, principal.familyId());
        requiredConversation(conversationId, principal.familyId(), babyId);
        var message = mapper.findAssistantMessage(messageId, conversationId, principal.familyId(), babyId);
        if (message == null) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "AI 消息不存在");
        var emitter = aiStreamHub.subscribe(message);
        if (emitter == null) throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "AI 流服务繁忙，请稍后重试");
        return emitter;
    }

    public AiConversationDetail question(long babyId,
                                         long conversationId,
                                         DeviceSessionPrincipal principal,
                                         AiQuestionRequest request) {
        babyEventService.assertBabyAccess(babyId, principal.familyId());
        ensureProviderConfigured();
        var conversation = requiredConversation(conversationId, principal.familyId(), babyId);
        var duplicate = mapper.findMessageByRequest(conversationId, principal.familyId(), babyId,
                "USER", request.clientMessageId());
        if (duplicate != null) return detail(conversation, principal.familyId(), babyId);
        checkRateLimits(principal.userId(), principal.familyId(), babyId, conversationId);
        String content = request.content().trim();

        SubmitResult submitted = transactionTemplate.execute(status -> {
            var existing = mapper.findMessageByRequest(conversationId, principal.familyId(), babyId,
                    "USER", request.clientMessageId());
            if (existing != null) {
                return new SubmitResult(requiredConversation(conversationId, principal.familyId(), babyId), null, false);
            }
            if (mapper.beginResponse(conversationId, principal.familyId(), babyId) != 1) {
                throw new ResponseStatusException(HttpStatus.CONFLICT, "上一条分析仍在处理中，请完成后再提问");
            }
            mapper.insertUserMessage(conversationId, principal.familyId(), babyId, principal.userId(),
                    request.clientMessageId(), content);
            mapper.insertAssistantPlaceholder(conversationId, principal.familyId(), babyId, request.clientMessageId());
            var assistant = mapper.findMessageByRequest(conversationId, principal.familyId(), babyId,
                    "ASSISTANT", request.clientMessageId());
            return new SubmitResult(requiredConversation(conversationId, principal.familyId(), babyId), assistant, true);
        });
        if (submitted == null) throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, "提问创建失败");
        if (submitted.created()) enqueue(submitted.conversation(), submitted.assistant());
        realtimeHub.publishChanged(babyId);
        return get(babyId, conversationId, principal);
    }

    public AiConversationDetail retry(long babyId, long conversationId, DeviceSessionPrincipal principal) {
        babyEventService.assertBabyAccess(babyId, principal.familyId());
        ensureProviderConfigured();
        var conversation = requiredConversation(conversationId, principal.familyId(), babyId);
        if (!"FAILED".equals(conversation.status())) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "当前会话不需要重试");
        }
        checkRateLimits(principal.userId(), principal.familyId(), babyId, conversationId);
        var failedMessage = mapper.findLastFailedAssistant(conversationId, principal.familyId(), babyId);
        if (failedMessage == null) throw new ResponseStatusException(HttpStatus.CONFLICT, "没有可重试的分析");
        SubmitResult submitted = aiStreamHub.serialized(failedMessage.id(), () -> {
            var result = transactionTemplate.execute(status -> {
                var failed = mapper.findLastFailedAssistant(conversationId, principal.familyId(), babyId);
                if (failed == null) throw new ResponseStatusException(HttpStatus.CONFLICT, "没有可重试的分析");
                String nextStatus = mapper.countConversationQuestions(conversationId, principal.familyId(), babyId) == 0
                        ? "ANALYZING" : "RESPONDING";
                if (mapper.beginRetry(conversationId, principal.familyId(), babyId, nextStatus) != 1
                        || mapper.resetFailedAssistant(failed.id(), conversationId, principal.familyId(), babyId) != 1) {
                    throw new ResponseStatusException(HttpStatus.CONFLICT, "分析已被其他设备重试");
                }
                return new SubmitResult(requiredConversation(conversationId, principal.familyId(), babyId), failed, true);
            });
            if (result == null) throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, "重试失败");
            enqueue(result.conversation(), result.assistant());
            return result;
        });
        realtimeHub.publishChanged(babyId);
        return get(babyId, conversationId, principal);
    }

    public void archive(long babyId, long conversationId, DeviceSessionPrincipal principal) {
        babyEventService.assertBabyAccess(babyId, principal.familyId());
        var cancelledMessageIds = transactionTemplate.execute(status -> {
            var pending = mapper.listMessages(conversationId, principal.familyId(), babyId).stream()
                    .filter(message -> "ASSISTANT".equals(message.role()) && "PENDING".equals(message.status()))
                    .map(AiMessageRow::id)
                    .toList();
            var cancelled = new ArrayList<Long>();
            for (long messageId : pending) {
                if (mapper.failAssistant(messageId, conversationId, principal.familyId(), babyId,
                        "AI_REQUEST_CANCELLED") == 1) {
                    cancelled.add(messageId);
                }
            }
            if (mapper.archiveConversation(conversationId, principal.familyId(), babyId) != 1) {
                throw new ResponseStatusException(HttpStatus.NOT_FOUND, "AI 会话不存在");
            }
            return cancelled;
        });
        if (cancelledMessageIds != null) {
            cancelledMessageIds.forEach(messageId -> aiStreamHub.failed(messageId, "AI_REQUEST_CANCELLED"));
        }
        realtimeHub.publishChanged(babyId);
    }

    @Scheduled(fixedDelay = 60_000, initialDelay = 60_000)
    void recoverStaleRequests() {
        for (var assistant : mapper.findStalePendingMessages()) {
            aiStreamHub.serialized(assistant.id(), () -> {
                if (aiStreamHub.isTracked(assistant.id())) return null;
                if (mapper.claimStaleConversation(assistant.conversationId(), assistant.familyId(), assistant.babyId()) == 1) {
                    var conversation = mapper.findConversation(
                            assistant.familyId(), assistant.babyId(), assistant.conversationId());
                    if (conversation != null) enqueue(conversation, assistant);
                }
                return null;
            });
        }
    }

    private void enqueue(AiConversationRow conversation, AiMessageRow assistant) {
        if (assistant == null) {
            fail(conversation, null, "AI_REQUEST_INVALID");
            return;
        }
        aiStreamHub.serialized(assistant.id(), () -> {
            enqueueOwned(conversation, assistant);
            return null;
        });
    }

    private void enqueueOwned(AiConversationRow conversation, AiMessageRow assistant) {
        if (!aiStreamHub.prepare(assistant.id())) {
            fail(conversation, assistant.id(), "AI_QUEUE_FULL");
            return;
        }
        try {
            aiExecutor.execute(() -> process(conversation, assistant));
        } catch (RejectedExecutionException e) {
            fail(conversation, assistant.id(), "AI_QUEUE_FULL");
        }
    }

    private void process(AiConversationRow conversation, AiMessageRow assistant) {
        try {
            var current = mapper.findConversation(conversation.familyId(), conversation.babyId(), conversation.id());
            if (current == null || !("ANALYZING".equals(current.status()) || "RESPONDING".equals(current.status()))) {
                fail(conversation, assistant.id(), "AI_REQUEST_CANCELLED");
                return;
            }
            aiStreamHub.started(assistant.id());
            var built = snapshotService.create(conversation.id(), conversation.familyId(), conversation.babyId());
            if (mapper.attachSnapshot(assistant.id(), built.row().id(), conversation.id(),
                    conversation.familyId(), conversation.babyId()) != 1) {
                throw new AiProviderException("AI_STATE_CONFLICT");
            }
            var question = mapper.findMessageByRequest(conversation.id(), conversation.familyId(), conversation.babyId(),
                    "USER", assistant.clientMessageId());
            String requestPrompt;
            List<DeepSeekClient.PromptMessage> history = List.of();
            DeepSeekClient.SearchPolicy requestedSearchPolicy;
            if (question == null) {
                requestPrompt = promptBuilder.initialRequest(built.promptText());
                requestedSearchPolicy = DeepSeekClient.SearchPolicy.REQUIRED;
            } else {
                var recent = mapper.findRecentCompletedMessages(conversation.id(), conversation.familyId(), conversation.babyId());
                history = promptHistory(recent, question.id());
                requestPrompt = promptBuilder.followUpRequest(built.promptText(), question.content());
                requestedSearchPolicy = searchPolicyFor(question.content());
            }
            boolean searchUsed = false;
            if (requestedSearchPolicy != DeepSeekClient.SearchPolicy.NONE) {
                var references = deepSeekClient.streamChat(
                        AiPromptBuilder.REFERENCE_SEARCH_SYSTEM_PROMPT,
                        List.of(),
                        promptBuilder.referenceSearchRequest(question == null ? null : question.content()),
                        DeepSeekClient.SearchPolicy.REQUIRED,
                        ignored -> {}
                );
                if (!references.searchUsed()) throw new AiProviderException("AI_SEARCH_UNAVAILABLE");
                requestPrompt = promptBuilder.withGeneralReferences(requestPrompt, references.content());
                searchUsed = true;
            }
            var responseProfile = question == null
                    ? DeepSeekClient.ResponseProfile.INITIAL_ANALYSIS
                    : DeepSeekClient.ResponseProfile.FOLLOW_UP;
            var completion = deepSeekClient.streamChat(AiPromptBuilder.SYSTEM_PROMPT, history, requestPrompt,
                    DeepSeekClient.SearchPolicy.NONE, responseProfile,
                    delta -> aiStreamHub.delta(assistant.id(), delta));
            String answer = completion.content();
            boolean persistedSearchUsed = searchUsed;
            transactionTemplate.executeWithoutResult(status -> {
                if (mapper.completeAssistant(assistant.id(), built.row().id(), conversation.id(),
                        conversation.familyId(), conversation.babyId(), answer, persistedSearchUsed) != 1) {
                    throw new AiProviderException("AI_STATE_CONFLICT");
                }
                if (mapper.completeConversation(conversation.id(), conversation.familyId(), conversation.babyId()) != 1) {
                    throw new AiProviderException("AI_STATE_CONFLICT");
                }
            });
            aiStreamHub.completed(assistant.id(), answer, built.row().id());
            realtimeHub.publishChanged(conversation.babyId());
        } catch (AiProviderException e) {
            fail(conversation, assistant.id(), e.errorCode());
        } catch (RuntimeException e) {
            LOGGER.error("AI processing failed: conversationId={}, babyId={}, type={}",
                    conversation.id(), conversation.babyId(), e.getClass().getSimpleName(), e);
            fail(conversation, assistant.id(), "AI_INTERNAL_ERROR");
        }
    }

    private void fail(AiConversationRow conversation, Long assistantId, String errorCode) {
        var persistFailure = (Runnable) () -> transactionTemplate.executeWithoutResult(status -> {
            if (assistantId != null) {
                mapper.failAssistant(assistantId, conversation.id(), conversation.familyId(), conversation.babyId(), errorCode);
            }
            mapper.failConversation(conversation.id(), conversation.familyId(), conversation.babyId(), errorCode);
        });
        if (assistantId == null) {
            persistFailure.run();
        } else {
            aiStreamHub.serialized(assistantId, () -> {
                persistFailure.run();
                aiStreamHub.failed(assistantId, errorCode);
                return null;
            });
        }
        realtimeHub.publishChanged(conversation.babyId());
    }

    private void checkRateLimits(long userId, long familyId, long babyId, Long conversationId) {
        if (mapper.countUserRequestsLastHour(userId, familyId, babyId) >= USER_HOURLY_LIMIT
                || mapper.countFamilyRequestsLastDay(familyId, babyId) >= FAMILY_DAILY_LIMIT
                || mapper.countFamilySnapshotsLastDay(familyId, babyId) >= FAMILY_DAILY_LIMIT) {
            throw new ResponseStatusException(HttpStatus.TOO_MANY_REQUESTS, "AI 请求过于频繁，请稍后再试");
        }
        if (conversationId != null
                && mapper.countConversationQuestions(conversationId, familyId, babyId) >= CONVERSATION_QUESTION_LIMIT) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "该会话已达到提问上限，请新建分析会话");
        }
    }

    private void ensureProviderConfigured() {
        try {
            deepSeekClient.ensureConfigured();
        } catch (AiProviderException e) {
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "AI 服务尚未配置");
        }
    }

    private AiConversationRow requiredConversation(long conversationId, long familyId, long babyId) {
        var conversation = mapper.findConversation(familyId, babyId, conversationId);
        if (conversation == null) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "AI 会话不存在");
        return conversation;
    }

    private AiConversationDetail detail(AiConversationRow row, long familyId, long babyId) {
        return new AiConversationDetail(
                row.id(), row.title(), row.status(), row.model(), row.lastErrorCode(),
                row.createdAt(), row.updatedAt(), snapshot(mapper.findLatestSnapshot(row.id(), familyId, babyId)),
                mapper.listMessages(row.id(), familyId, babyId).stream().map(this::message).toList()
        );
    }

    @SuppressWarnings("unchecked")
    private AiSnapshotResponse snapshot(AiSnapshotRow row) {
        if (row == null) return null;
        try {
            Map<String, Object> dashboard = objectMapper.readValue(row.dashboard(), Map.class);
            return new AiSnapshotResponse(row.id(), row.snapshotAt(), row.rangeStart(), row.rangeEnd(),
                    row.sourceEventCount(), row.promptVersion(), dashboard);
        } catch (JacksonException e) {
            throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, "分析数据快照损坏");
        }
    }

    private AiMessageResponse message(AiMessageRow row) {
        return new AiMessageResponse(row.id(), row.role(), row.status(), row.content(), row.authorName(),
                row.snapshotId(), row.snapshotAt(), row.createdAt(), row.errorCode(), row.searchUsed());
    }

    static List<DeepSeekClient.PromptMessage> promptHistory(List<AiMessageRow> newestFirst,
                                                            long currentQuestionId) {
        if (newestFirst == null || newestFirst.isEmpty()) return List.of();
        var result = new ArrayList<DeepSeekClient.PromptMessage>(HISTORY_MESSAGE_LIMIT);
        for (var message : newestFirst) {
            if (result.size() >= HISTORY_MESSAGE_LIMIT) break;
            if (message == null || message.id() == currentQuestionId || message.content() == null
                    || message.content().isBlank()) {
                continue;
            }
            String role;
            String content = message.content();
            if ("USER".equals(message.role())) {
                role = "user";
            } else if ("ASSISTANT".equals(message.role())) {
                role = "assistant";
                String cutoff = message.snapshotAt() == null
                        ? "未知"
                        : message.snapshotAt().format(HISTORY_TIME);
                content = "[历史回答依据的数据截止：" + cutoff + "；不得作为当前事实]\n" + content;
            } else {
                continue;
            }
            result.add(new DeepSeekClient.PromptMessage(role, content));
        }
        Collections.reverse(result);
        return List.copyOf(result);
    }

    static DeepSeekClient.SearchPolicy searchPolicyFor(String question) {
        if (question == null || question.isBlank()) return DeepSeekClient.SearchPolicy.REQUIRED;
        String normalized = question.strip().toLowerCase(Locale.ROOT);
        if (OUT_OF_SCOPE_TERMS.stream().anyMatch(normalized::contains)) {
            return DeepSeekClient.SearchPolicy.NONE;
        }
        if (INTERPRETIVE_QUESTION_TERMS.stream().anyMatch(normalized::contains)) {
            return DeepSeekClient.SearchPolicy.REQUIRED;
        }
        if (RECORD_LOOKUP_TERMS.stream().anyMatch(normalized::contains)) {
            return DeepSeekClient.SearchPolicy.NONE;
        }
        return DeepSeekClient.SearchPolicy.REQUIRED;
    }

    private record SubmitResult(AiConversationRow conversation, AiMessageRow assistant, boolean created) {}
}
