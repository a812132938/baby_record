package com.babyrecord.service;

import com.babyrecord.mapper.AiConversationMapper;
import com.babyrecord.model.AiConversationRow;
import com.babyrecord.model.AiMessageRow;
import com.babyrecord.model.AiSnapshotRow;
import com.babyrecord.realtime.AiStreamHub;
import com.babyrecord.realtime.RealtimeHub;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.ObjectMapper;

import java.lang.reflect.Method;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ThreadPoolExecutor;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AiConversationProcessTest {
    @Test
    void initialAnalysisSearchesSafeReferencesBeforeGeneratingWithoutSearch() throws Exception {
        var fixture = fixture(null, List.of(), true);

        invokeProcess(fixture.service, fixture.conversation, fixture.assistant);

        verify(fixture.deepSeekClient).streamChat(
                eq(AiPromptBuilder.REFERENCE_SEARCH_SYSTEM_PROMPT), eq(List.of()), eq("safe-reference-prompt"),
                eq(DeepSeekClient.SearchPolicy.REQUIRED), any());
        verify(fixture.deepSeekClient).streamChat(
                eq(AiPromptBuilder.SYSTEM_PROMPT), eq(List.of()), eq("analysis-with-references"),
                eq(DeepSeekClient.SearchPolicy.NONE), eq(DeepSeekClient.ResponseProfile.INITIAL_ANALYSIS), any());
    }

    @Test
    void interpretiveFollowUpSearchesSafeReferencesBeforeGeneratingWithoutSearch() throws Exception {
        var question = message(30, "USER", "这些奶量够不够？", null);
        var fixture = fixture(question, List.of(question), true);

        invokeProcess(fixture.service, fixture.conversation, fixture.assistant);

        verify(fixture.deepSeekClient).streamChat(
                eq(AiPromptBuilder.REFERENCE_SEARCH_SYSTEM_PROMPT), eq(List.of()), eq("safe-reference-prompt"),
                eq(DeepSeekClient.SearchPolicy.REQUIRED), any());
        verify(fixture.deepSeekClient).streamChat(
                eq(AiPromptBuilder.SYSTEM_PROMPT), eq(List.of()), eq("analysis-with-references"),
                eq(DeepSeekClient.SearchPolicy.NONE), eq(DeepSeekClient.ResponseProfile.FOLLOW_UP), any());
    }

    @Test
    void recordLookupFollowUpDoesNotRequestWebSearch() throws Exception {
        var question = message(30, "USER", "宝宝今天一共喝了多少毫升？", null);
        var fixture = fixture(question, List.of(question), false);

        invokeProcess(fixture.service, fixture.conversation, fixture.assistant);

        verify(fixture.deepSeekClient).streamChat(
                eq(AiPromptBuilder.SYSTEM_PROMPT), eq(List.of()), eq("follow-up-prompt"),
                eq(DeepSeekClient.SearchPolicy.NONE), eq(DeepSeekClient.ResponseProfile.FOLLOW_UP), any());
        verify(fixture.deepSeekClient, org.mockito.Mockito.never()).streamChat(
                eq(AiPromptBuilder.REFERENCE_SEARCH_SYSTEM_PROMPT), any(), anyString(),
                eq(DeepSeekClient.SearchPolicy.REQUIRED), any());
    }

    @Test
    @SuppressWarnings("unchecked")
    void followUpPassesTwelveHistoricalMessagesWithoutTheCurrentQuestion() throws Exception {
        var question = message(100, "USER", "CURRENT-QUESTION", null);
        var newestFirst = new ArrayList<AiMessageRow>();
        newestFirst.add(question);
        for (long id = 99; id >= 88; id--) {
            String role = id % 2 == 0 ? "USER" : "ASSISTANT";
            newestFirst.add(message(id, role, "history-" + id,
                    "ASSISTANT".equals(role) ? LocalDateTime.of(2026, 8, 22, 9, (int) (id % 60)) : null));
        }
        var fixture = fixture(question, newestFirst, true);

        invokeProcess(fixture.service, fixture.conversation, fixture.assistant);

        var history = ArgumentCaptor.forClass(List.class);
        verify(fixture.deepSeekClient).streamChat(eq(AiPromptBuilder.SYSTEM_PROMPT), history.capture(),
                eq("analysis-with-references"), eq(DeepSeekClient.SearchPolicy.NONE),
                eq(DeepSeekClient.ResponseProfile.FOLLOW_UP), any());
        List<DeepSeekClient.PromptMessage> captured = history.getValue();
        assertThat(captured).hasSize(12);
        assertThat(captured).extracting(DeepSeekClient.PromptMessage::content)
                .noneMatch(content -> content.contains("CURRENT-QUESTION"));
        assertThat(captured.getFirst().content()).isEqualTo("history-88");
        assertThat(captured.getLast().content())
                .startsWith("[历史回答依据的数据截止：2026-08-22 09:39；不得作为当前事实]\n")
                .endsWith("history-99");
    }

    @Test
    void referenceSearchUsageIsPersistedOnTheAssistantMessage() throws Exception {
        var fixture = fixture(null, List.of(), true);

        invokeProcess(fixture.service, fixture.conversation, fixture.assistant);

        verify(fixture.mapper).completeAssistant(fixture.assistant.id(), fixture.snapshot.id(),
                fixture.conversation.id(), fixture.conversation.familyId(), fixture.conversation.babyId(),
                "provider-answer", true);
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private static Fixture fixture(AiMessageRow question, List<AiMessageRow> recent,
                                   boolean referenceSearchUsed) {
        var mapper = mock(AiConversationMapper.class);
        var snapshotService = mock(AiSnapshotService.class);
        var promptBuilder = mock(AiPromptBuilder.class);
        var deepSeekClient = mock(DeepSeekClient.class);
        var aiStreamHub = mock(AiStreamHub.class);
        var realtimeHub = mock(RealtimeHub.class);
        var transactionTemplate = mock(TransactionTemplate.class);
        var conversation = conversation();
        var assistant = message(41, "ASSISTANT", null, null);
        var snapshot = snapshot();

        when(mapper.findConversation(conversation.familyId(), conversation.babyId(), conversation.id()))
                .thenReturn(conversation);
        when(snapshotService.create(conversation.id(), conversation.familyId(), conversation.babyId()))
                .thenReturn(new AiSnapshotService.BuiltSnapshot(snapshot, "snapshot-prompt"));
        when(mapper.attachSnapshot(assistant.id(), snapshot.id(), conversation.id(),
                conversation.familyId(), conversation.babyId())).thenReturn(1);
        when(mapper.findMessageByRequest(conversation.id(), conversation.familyId(), conversation.babyId(),
                "USER", assistant.clientMessageId())).thenReturn(question);
        when(mapper.findRecentCompletedMessages(conversation.id(), conversation.familyId(), conversation.babyId()))
                .thenReturn(recent);
        when(promptBuilder.initialRequest("snapshot-prompt")).thenReturn("initial-prompt");
        if (question != null) {
            when(promptBuilder.followUpRequest("snapshot-prompt", question.content())).thenReturn("follow-up-prompt");
        }
        when(promptBuilder.referenceSearchRequest(question == null ? null : question.content()))
                .thenReturn("safe-reference-prompt");
        when(promptBuilder.withGeneralReferences(anyString(), eq("general-references")))
                .thenReturn("analysis-with-references");
        when(deepSeekClient.streamChat(anyString(), any(), anyString(), any(), any()))
                .thenAnswer(invocation -> AiPromptBuilder.REFERENCE_SEARCH_SYSTEM_PROMPT.equals(invocation.getArgument(0))
                        ? new DeepSeekClient.CompletionResult("general-references", referenceSearchUsed)
                        : new DeepSeekClient.CompletionResult("provider-answer", false));
        when(deepSeekClient.streamChat(anyString(), any(), anyString(), any(), any(), any()))
                .thenReturn(new DeepSeekClient.CompletionResult("provider-answer", false));
        when(mapper.completeAssistant(anyLong(), anyLong(), anyLong(), anyLong(), anyLong(), anyString(),
                org.mockito.ArgumentMatchers.anyBoolean())).thenReturn(1);
        when(mapper.completeConversation(conversation.id(), conversation.familyId(), conversation.babyId())).thenReturn(1);
        doAnswer(invocation -> {
            var callback = invocation.getArgument(0, java.util.function.Consumer.class);
            callback.accept(mock(TransactionStatus.class));
            return null;
        }).when(transactionTemplate).executeWithoutResult(any());

        var service = new AiConversationService(mapper, mock(BabyEventService.class), snapshotService,
                promptBuilder, deepSeekClient, aiStreamHub, realtimeHub, new ObjectMapper(),
                transactionTemplate, mock(ThreadPoolExecutor.class));
        return new Fixture(service, mapper, deepSeekClient, conversation, assistant, snapshot);
    }

    private static void invokeProcess(AiConversationService service, AiConversationRow conversation,
                                      AiMessageRow assistant) throws Exception {
        Method process = AiConversationService.class.getDeclaredMethod(
                "process", AiConversationRow.class, AiMessageRow.class);
        process.setAccessible(true);
        process.invoke(service, conversation, assistant);
    }

    private static AiConversationRow conversation() {
        var now = LocalDateTime.of(2026, 8, 22, 10, 0);
        return new AiConversationRow(10, 3, 9, 4, "conversation-request", "宝宝分析", "ANALYZING",
                "deepseek-v4-flash", null, now, null, now, now);
    }

    private static AiMessageRow message(long id, String role, String content, LocalDateTime snapshotAt) {
        var now = LocalDateTime.of(2026, 8, 22, 10, 0);
        return new AiMessageRow(id, 10, 3, 9, "USER".equals(role) ? 4L : null, "request-" + id,
                role, "ASSISTANT".equals(role) ? "PENDING" : "COMPLETED", content,
                snapshotAt == null ? null : id + 1_000, null, false,
                "USER".equals(role) ? "家属" : null, snapshotAt, now, now);
    }

    private static AiSnapshotRow snapshot() {
        var now = LocalDateTime.of(2026, 8, 22, 10, 0);
        return new AiSnapshotRow(501, 10, 3, 9, now, now.minusDays(2), now, 24,
                "baby-analysis-v4", "{}", "snapshot-prompt", now);
    }

    private record Fixture(AiConversationService service, AiConversationMapper mapper,
                           DeepSeekClient deepSeekClient, AiConversationRow conversation,
                           AiMessageRow assistant, AiSnapshotRow snapshot) {}
}
