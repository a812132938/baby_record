package com.babyrecord.service;

import com.babyrecord.auth.DeviceSessionPrincipal;
import com.babyrecord.mapper.AiConversationMapper;
import com.babyrecord.model.AiConversationRow;
import com.babyrecord.model.AiMessageRow;
import com.babyrecord.realtime.AiStreamHub;
import com.babyrecord.realtime.RealtimeHub;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.transaction.TransactionStatus;
import org.springframework.web.server.ResponseStatusException;
import tools.jackson.databind.ObjectMapper;

import java.time.LocalDateTime;
import java.util.concurrent.ThreadPoolExecutor;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.never;
import static org.mockito.ArgumentMatchers.any;

class AiConversationStreamScopeTest {

    @Test
    void streamChecksBabyConversationAndAssistantMessageWithinTheAuthenticatedFamily() {
        var mapper = mock(AiConversationMapper.class);
        var babyService = mock(BabyEventService.class);
        var hub = new AiStreamHub();
        var principal = new DeviceSessionPrincipal(1L, 2L, 3L, "妈妈", "ADMIN");
        when(mapper.findConversation(3L, 9L, 10L)).thenReturn(mock(AiConversationRow.class));
        when(mapper.findAssistantMessage(11L, 10L, 3L, 9L)).thenReturn(message());
        var service = service(mapper, babyService, hub);

        var emitter = service.stream(9L, 10L, 11L, principal);

        verify(babyService).assertBabyAccess(9L, 3L);
        verify(mapper).findConversation(3L, 9L, 10L);
        verify(mapper).findAssistantMessage(11L, 10L, 3L, 9L);
        emitter.complete();
    }

    @Test
    void streamDoesNotExposeAMessageOutsideTheScopedConversation() {
        var mapper = mock(AiConversationMapper.class);
        var hub = new AiStreamHub();
        when(mapper.findConversation(3L, 9L, 10L)).thenReturn(mock(AiConversationRow.class));
        var service = service(mapper, mock(BabyEventService.class), hub);
        var principal = new DeviceSessionPrincipal(1L, 2L, 3L, "妈妈", "ADMIN");

        assertThatThrownBy(() -> service.stream(9L, 10L, 99L, principal))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("404 NOT_FOUND");
    }

    @Test
    @SuppressWarnings("unchecked")
    void archiveCancelsPendingAssistantBeforeArchivingTheConversation() {
        var mapper = mock(AiConversationMapper.class);
        var babyService = mock(BabyEventService.class);
        var hub = new AiStreamHub();
        var transaction = mock(TransactionTemplate.class);
        when(transaction.execute(org.mockito.ArgumentMatchers.any())).thenAnswer(invocation -> {
            var callback = (org.springframework.transaction.support.TransactionCallback<Object>) invocation.getArgument(0);
            return callback.doInTransaction(mock(TransactionStatus.class));
        });
        when(mapper.listMessages(10L, 3L, 9L)).thenReturn(java.util.List.of(message()));
        when(mapper.failAssistant(11L, 10L, 3L, 9L, "AI_REQUEST_CANCELLED")).thenReturn(1);
        when(mapper.archiveConversation(10L, 3L, 9L)).thenReturn(1);
        hub.prepare(11L);
        var service = service(mapper, babyService, hub, transaction);
        var principal = new DeviceSessionPrincipal(1L, 2L, 3L, "妈妈", "ADMIN");

        service.archive(9L, 10L, principal);

        var ordered = inOrder(mapper);
        ordered.verify(mapper).failAssistant(11L, 10L, 3L, 9L, "AI_REQUEST_CANCELLED");
        ordered.verify(mapper).archiveConversation(10L, 3L, 9L);
        assertThat(hub.subscribe(message())).isNotNull();
    }

    @Test
    void staleRecoveryDoesNotDuplicateALocallyQueuedOrRunningMessage() {
        var mapper = mock(AiConversationMapper.class);
        var hub = new AiStreamHub();
        when(mapper.findStalePendingMessages()).thenReturn(java.util.List.of(message()));
        hub.prepare(11L);
        var service = service(mapper, mock(BabyEventService.class), hub);

        service.recoverStaleRequests();

        verify(mapper, never()).claimStaleConversation(10L, 3L, 9L);
    }

    @Test
    void passivePendingSubscriberDoesNotBlockCrashRecoveryFromClaimingAndEnqueueing() {
        var mapper = mock(AiConversationMapper.class);
        var hub = new AiStreamHub();
        var executor = mock(ThreadPoolExecutor.class);
        when(mapper.findStalePendingMessages()).thenReturn(java.util.List.of(message()));
        when(mapper.claimStaleConversation(10L, 3L, 9L)).thenReturn(1);
        when(mapper.findConversation(3L, 9L, 10L)).thenReturn(mock(AiConversationRow.class));
        assertThat(hub.subscribe(message())).isNotNull();
        assertThat(hub.isTracked(11L)).isFalse();
        var service = service(mapper, mock(BabyEventService.class), hub,
                mock(TransactionTemplate.class), executor);

        service.recoverStaleRequests();

        verify(mapper).claimStaleConversation(10L, 3L, 9L);
        verify(executor).execute(any(Runnable.class));
        assertThat(hub.isTracked(11L)).isTrue();
    }

    private static AiConversationService service(AiConversationMapper mapper,
                                                 BabyEventService babyService,
                                                 AiStreamHub hub) {
        return service(mapper, babyService, hub, mock(TransactionTemplate.class));
    }

    private static AiConversationService service(AiConversationMapper mapper,
                                                 BabyEventService babyService,
                                                 AiStreamHub hub,
                                                 TransactionTemplate transaction) {
        return service(mapper, babyService, hub, transaction, mock(ThreadPoolExecutor.class));
    }

    private static AiConversationService service(AiConversationMapper mapper,
                                                 BabyEventService babyService,
                                                 AiStreamHub hub,
                                                 TransactionTemplate transaction,
                                                 ThreadPoolExecutor executor) {
        return new AiConversationService(mapper, babyService, mock(AiSnapshotService.class),
                mock(AiPromptBuilder.class), mock(DeepSeekClient.class), hub, mock(RealtimeHub.class),
                new ObjectMapper(), transaction, executor);
    }

    private static AiMessageRow message() {
        var now = LocalDateTime.now();
        return new AiMessageRow(11L, 10L, 3L, 9L, null, "request-id", "ASSISTANT", "PENDING",
                null, null, null, false, null, null, now, now);
    }
}
