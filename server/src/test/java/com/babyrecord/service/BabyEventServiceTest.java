package com.babyrecord.service;

import com.babyrecord.dto.BabySummary;
import com.babyrecord.dto.FeedingRequest;
import com.babyrecord.dto.UpdateBabyRequest;
import com.babyrecord.dto.UpdateEventRequest;
import com.babyrecord.mapper.BabyEventMapper;
import com.babyrecord.model.BabyEvent;
import com.babyrecord.realtime.RealtimeHub;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.web.server.ResponseStatusException;
import tools.jackson.databind.ObjectMapper;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.any;
import org.mockito.ArgumentCaptor;

class BabyEventServiceTest {

    @Test
    void createsDirectBreastfeedWithCanonicalSideDurationsAndNoAmount() {
        var mapper = writableMapper();
        var realtimeHub = mock(RealtimeHub.class);
        var service = new BabyEventService(mapper, realtimeHub, new ObjectMapper());
        var eventTime = LocalDateTime.of(2026, 8, 21, 9, 30);

        service.feeding(42L, 7L, 11L, new FeedingRequest(
                "direct_breastfeed", null, 300, 180, "right", List.of(
                        new FeedingRequest.FeedingSegment("LEFT", 300),
                        new FeedingRequest.FeedingSegment("RIGHT", 180)
                ),
                null, null, null, eventTime, "00000000-0000-0000-0000-000000000001"
        ));

        var captor = ArgumentCaptor.forClass(BabyEvent.class);
        verify(mapper).insert(captor.capture());
        var inserted = captor.getValue();
        assertThat(inserted.getEventType()).isEqualTo("DIRECT_BREASTFEED");
        assertThat(inserted.getAmountMl()).isNull();
        assertThat(inserted.getStartTime()).isEqualTo(eventTime);
        assertThat(inserted.getEndTime()).isEqualTo(eventTime.plusSeconds(480));
        assertThat(inserted.getEventData()).isEqualTo(
                "{\"schemaVersion\":1,\"leftSeconds\":300,\"rightSeconds\":180,\"lastSide\":\"RIGHT\",\"segments\":[{\"side\":\"LEFT\",\"seconds\":300},{\"side\":\"RIGHT\",\"seconds\":180}]}"
        );
        verify(realtimeHub).publishChanged(42L);
    }

    @Test
    void createsSeparateBottleAndFormulaTypes() {
        var mapper = writableMapper();
        var service = new BabyEventService(mapper, mock(RealtimeHub.class), new ObjectMapper());

        service.feeding(42L, 7L, 11L, new FeedingRequest(
                "BOTTLE_BREAST_MILK", 90, null, null, null, null,
                null, null, null, null, null
        ));
        service.feeding(42L, 7L, 11L, new FeedingRequest(
                "FORMULA_FEED", 120, null, null, null, null,
                null, null, null, null, null
        ));

        var captor = ArgumentCaptor.forClass(BabyEvent.class);
        verify(mapper, org.mockito.Mockito.times(2)).insert(captor.capture());
        assertThat(captor.getAllValues()).extracting(BabyEvent::getEventType)
                .containsExactly("BOTTLE_BREAST_MILK", "FORMULA_FEED");
        assertThat(captor.getAllValues()).extracting(BabyEvent::getAmountMl)
                .containsExactly(90, 120);
    }

    @Test
    void duplicateReplayFallsBackToClientEventIdWhenDriverDoesNotReturnGeneratedId() {
        var mapper = mock(BabyEventMapper.class);
        var original = new BabyEvent();
        original.setId(77L);
        original.setBabyId(42L);
        original.setOperatorId(11L);
        original.setOperatorName("妈妈");
        original.setClientEventId("00000000-0000-0000-0000-000000000077");
        original.setEventType("FORMULA_FEED");
        original.setAmountMl(90);
        when(mapper.countBabyInFamily(42L, 7L)).thenReturn(1);
        when(mapper.insert(any(BabyEvent.class))).thenReturn(1);
        when(mapper.findByClientEventId(42L, original.getClientEventId())).thenReturn(original);
        var service = new BabyEventService(mapper, mock(RealtimeHub.class), new ObjectMapper());

        var replayed = service.feeding(42L, 7L, 22L, new FeedingRequest(
                "FORMULA_FEED", 120, null, null, null, null,
                null, null, null, null, original.getClientEventId()
        ));

        assertThat(replayed).isSameAs(original);
        assertThat(replayed.getAmountMl()).isEqualTo(90);
        assertThat(replayed.getOperatorId()).isEqualTo(11L);
        verify(mapper, never()).findById(org.mockito.ArgumentMatchers.eq(42L), org.mockito.ArgumentMatchers.anyLong());
        verify(mapper).findByClientEventId(42L, original.getClientEventId());
    }

    @Test
    void missingGeneratedIdWithoutIdempotencyKeyFailsClearlyInsteadOfThrowingNullPointer() {
        var mapper = mock(BabyEventMapper.class);
        when(mapper.countBabyInFamily(42L, 7L)).thenReturn(1);
        when(mapper.insert(any(BabyEvent.class))).thenReturn(1);
        var service = new BabyEventService(mapper, mock(RealtimeHub.class), new ObjectMapper());

        assertThatThrownBy(() -> service.feeding(42L, 7L, 11L, new FeedingRequest(
                "FORMULA_FEED", 120, null, null, null, null,
                null, null, null, null, null
        ))).isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("服务端无法读取写入结果");
    }

    @Test
    void pumpingUsesSideSumAndAllowsOmittedDuration() {
        var mapper = writableMapper();
        var service = new BabyEventService(mapper, mock(RealtimeHub.class), new ObjectMapper());

        service.feeding(42L, 7L, 11L, new FeedingRequest(
                "PUMPING", null, null, null, null, null,
                55, 65, null, null, null
        ));

        var captor = ArgumentCaptor.forClass(BabyEvent.class);
        verify(mapper).insert(captor.capture());
        assertThat(captor.getValue().getAmountMl()).isEqualTo(120);
        assertThat(captor.getValue().getEndTime()).isNull();
        assertThat(captor.getValue().getEventData()).isEqualTo(
                "{\"schemaVersion\":1,\"leftMl\":55,\"rightMl\":65,\"durationSeconds\":null}"
        );
    }

    @Test
    void rejectsPumpingWhenSubmittedTotalDiffersFromSideSum() {
        var mapper = writableMapper();
        var service = new BabyEventService(mapper, mock(RealtimeHub.class), new ObjectMapper());

        assertThatThrownBy(() -> service.feeding(42L, 7L, 11L, new FeedingRequest(
                "PUMPING", 130, null, null, null, null,
                55, 65, 900, null, null
        ))).isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("泵奶总量必须等于左右侧之和");

        verify(mapper, never()).insert(any());
    }

    @Test
    void rejectsDirectBreastfeedWithoutAnyDuration() {
        var mapper = writableMapper();
        var service = new BabyEventService(mapper, mock(RealtimeHub.class), new ObjectMapper());

        assertThatThrownBy(() -> service.feeding(42L, 7L, 11L, new FeedingRequest(
                "DIRECT_BREASTFEED", null, 0, 0, "LEFT", null,
                null, null, null, null, null
        ))).isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("亲喂总时长");
    }

    @Test
    void editingDirectBreastfeedRecomputesEndTimeFromCanonicalData() {
        var mapper = mock(BabyEventMapper.class);
        var event = new BabyEvent();
        event.setId(99L);
        event.setBabyId(42L);
        event.setEventType("DIRECT_BREASTFEED");
        event.setStartTime(LocalDateTime.of(2026, 8, 21, 8, 0));
        event.setEventData("{\"schemaVersion\":1,\"leftSeconds\":60,\"rightSeconds\":60,\"lastSide\":\"RIGHT\"}");
        when(mapper.countBabyInFamily(42L, 7L)).thenReturn(1);
        when(mapper.findById(42L, 99L)).thenReturn(event);
        when(mapper.updateEvent(org.mockito.ArgumentMatchers.anyLong(), org.mockito.ArgumentMatchers.anyLong(),
                any(), any(), any(), any(), any())).thenReturn(1);
        var service = new BabyEventService(mapper, mock(RealtimeHub.class), new ObjectMapper());
        var newStart = LocalDateTime.of(2026, 8, 21, 9, 0);
        var data = Map.<String, Object>of(
                "schemaVersion", 1,
                "leftSeconds", 300,
                "rightSeconds", 180,
                "lastSide", "RIGHT"
        );

        service.updateEvent(42L, 7L, 99L, new UpdateEventRequest(newStart, null, null, data, null));

        var endCaptor = ArgumentCaptor.forClass(LocalDateTime.class);
        var dataCaptor = ArgumentCaptor.forClass(String.class);
        verify(mapper).updateEvent(
                org.mockito.ArgumentMatchers.eq(42L), org.mockito.ArgumentMatchers.eq(99L),
                org.mockito.ArgumentMatchers.eq(newStart), endCaptor.capture(),
                org.mockito.ArgumentMatchers.isNull(), dataCaptor.capture(), org.mockito.ArgumentMatchers.isNull()
        );
        assertThat(endCaptor.getValue()).isEqualTo(newStart.plusSeconds(480));
        assertThat(dataCaptor.getValue()).contains(
                "\"schemaVersion\":1", "\"leftSeconds\":300", "\"rightSeconds\":180", "\"lastSide\":\"RIGHT\""
        );
    }

    @Test
    void dashboardUsesOnlyRecentPopularFeedAmounts() {
        var mapper = mock(BabyEventMapper.class);
        when(mapper.countBabyInFamily(42L, 7L)).thenReturn(1);
        when(mapper.findPopularFeedAmounts(42L)).thenReturn(List.of(80, 100));
        when(mapper.findTimeline(42L, 60)).thenReturn(List.of());
        var service = new BabyEventService(mapper, mock(RealtimeHub.class), new ObjectMapper());

        var dashboard = service.dashboard(42L, 7L);

        assertThat(dashboard.feedQuickAmounts()).containsExactly(80, 100);
    }

    @Test
    void dashboardReturnsNoQuickFeedAmountsWithoutHistory() {
        var mapper = mock(BabyEventMapper.class);
        when(mapper.countBabyInFamily(42L, 7L)).thenReturn(1);
        when(mapper.findPopularFeedAmounts(42L)).thenReturn(List.of());
        when(mapper.findTimeline(42L, 60)).thenReturn(List.of());
        var service = new BabyEventService(mapper, mock(RealtimeHub.class), new ObjectMapper());

        var dashboard = service.dashboard(42L, 7L);

        assertThat(dashboard.feedQuickAmounts()).isEmpty();
    }

    @Test
    void startSleepChecksFamilyAccessBeforeLockingAndReadingActiveSleep() {
        var mapper = mock(BabyEventMapper.class);
        var activeSleep = new BabyEvent();
        when(mapper.countBabyInFamily(42L, 7L)).thenReturn(1);
        when(mapper.lockBabyForSleep(42L, 7L)).thenReturn(42L);
        when(mapper.findActiveSleepForUpdate(42L)).thenReturn(activeSleep);
        var service = new BabyEventService(mapper, mock(RealtimeHub.class), new ObjectMapper());

        var result = service.startSleep(42L, 7L, 11L, null, "sleep-client-id");

        assertThat(result).isSameAs(activeSleep);
        var order = inOrder(mapper);
        order.verify(mapper).countBabyInFamily(42L, 7L);
        order.verify(mapper).lockBabyForSleep(42L, 7L);
        order.verify(mapper).findActiveSleepForUpdate(42L);
    }

    @Test
    void startSleepDoesNotLockABabyOutsideTheFamily() {
        var mapper = mock(BabyEventMapper.class);
        when(mapper.countBabyInFamily(42L, 7L)).thenReturn(0);
        var service = new BabyEventService(mapper, mock(RealtimeHub.class), new ObjectMapper());

        assertThatThrownBy(() -> service.startSleep(42L, 7L, 11L, null, "sleep-client-id"))
                .isInstanceOf(ResponseStatusException.class);

        verify(mapper, never()).lockBabyForSleep(42L, 7L);
        verify(mapper, never()).findActiveSleepForUpdate(42L);
    }

    @Test
    void endingSleepRecordsTheCurrentFamilyMemberSeparatelyFromTheStarter() {
        var mapper = mock(BabyEventMapper.class);
        var started = new BabyEvent();
        started.setId(99L);
        started.setBabyId(42L);
        started.setOperatorId(11L);
        started.setOperatorName("妈妈");
        started.setEventType("SLEEP");
        started.setStartTime(LocalDateTime.of(2026, 8, 21, 8, 0));
        var completed = new BabyEvent();
        completed.setId(99L);
        completed.setOperatorId(11L);
        completed.setOperatorName("妈妈");
        completed.setEndOperatorId(22L);
        completed.setEndOperatorName("爸爸");
        completed.setEventType("SLEEP");
        completed.setStartTime(started.getStartTime());
        completed.setEndTime(LocalDateTime.of(2026, 8, 21, 9, 0));
        when(mapper.countBabyInFamily(42L, 7L)).thenReturn(1);
        when(mapper.findById(42L, 99L)).thenReturn(started, completed);
        when(mapper.endSleep(42L, 99L, completed.getEndTime(), 22L)).thenReturn(1);
        var service = new BabyEventService(mapper, mock(RealtimeHub.class), new ObjectMapper());

        var result = service.endSleep(42L, 7L, 22L, 99L, completed.getEndTime());

        verify(mapper).endSleep(42L, 99L, completed.getEndTime(), 22L);
        assertThat(result.getOperatorName()).isEqualTo("妈妈");
        assertThat(result.getEndOperatorId()).isEqualTo(22L);
        assertThat(result.getEndOperatorName()).isEqualTo("爸爸");
    }

    @Test
    void endingSleepByClientEventIdAlsoRecordsTheCurrentFamilyMember() {
        var mapper = mock(BabyEventMapper.class);
        var started = new BabyEvent();
        started.setId(99L);
        started.setBabyId(42L);
        started.setOperatorId(11L);
        started.setEventType("SLEEP");
        started.setStartTime(LocalDateTime.of(2026, 8, 21, 8, 0));
        var completed = new BabyEvent();
        completed.setId(99L);
        completed.setEventType("SLEEP");
        completed.setEndOperatorId(22L);
        when(mapper.countBabyInFamily(42L, 7L)).thenReturn(1);
        when(mapper.findByClientEventId(42L, "sleep-client-id")).thenReturn(started);
        when(mapper.findById(42L, 99L)).thenReturn(completed);
        var endTime = LocalDateTime.of(2026, 8, 21, 9, 0);
        when(mapper.endSleep(42L, 99L, endTime, 22L)).thenReturn(1);
        var service = new BabyEventService(mapper, mock(RealtimeHub.class), new ObjectMapper());

        var result = service.endSleepByClientEventId(42L, 7L, 22L, "sleep-client-id", endTime);

        verify(mapper).endSleep(42L, 99L, endTime, 22L);
        assertThat(result.getEndOperatorId()).isEqualTo(22L);
    }

    @Test
    void updateBabyPersistsCompleteBirthProfile() {
        var mapper = mock(BabyEventMapper.class);
        var realtimeHub = mock(RealtimeHub.class);
        var birthday = LocalDate.of(2026, 8, 1);
        var request = new UpdateBabyRequest(" 小满 ", birthday, "GIRL", 3200);
        var expected = new BabySummary(42L, "小满", birthday, "GIRL", 3200);
        when(mapper.countBabyInFamily(42L, 7L)).thenReturn(1);
        when(mapper.updateBaby(42L, 7L, "小满", birthday, "GIRL", 3200)).thenReturn(1);
        when(mapper.findBabySummary(42L)).thenReturn(expected);
        var service = new BabyEventService(mapper, realtimeHub, new ObjectMapper());

        var updated = service.updateBaby(42L, 7L, request);

        assertThat(updated).isEqualTo(expected);
        verify(mapper).updateBaby(42L, 7L, "小满", birthday, "GIRL", 3200);
        verify(realtimeHub).publishChanged(42L);
    }

    @Test
    void publishesRealtimeChangeOnlyAfterTransactionCommit() {
        var mapper = mock(BabyEventMapper.class);
        var realtimeHub = mock(RealtimeHub.class);
        var birthday = LocalDate.of(2026, 8, 1);
        var request = new UpdateBabyRequest("小满", birthday, "GIRL", 3200);
        when(mapper.countBabyInFamily(42L, 7L)).thenReturn(1);
        when(mapper.updateBaby(42L, 7L, "小满", birthday, "GIRL", 3200)).thenReturn(1);
        when(mapper.findBabySummary(42L)).thenReturn(new BabySummary(42L, "小满", birthday, "GIRL", 3200));
        var service = new BabyEventService(mapper, realtimeHub, new ObjectMapper());

        TransactionSynchronizationManager.setActualTransactionActive(true);
        TransactionSynchronizationManager.initSynchronization();
        try {
            service.updateBaby(42L, 7L, request);

            verify(realtimeHub, never()).publishChanged(42L);
            var synchronizations = TransactionSynchronizationManager.getSynchronizations();
            assertThat(synchronizations).hasSize(1);

            synchronizations.forEach(synchronization -> synchronization.afterCommit());
            verify(realtimeHub).publishChanged(42L);
        } finally {
            TransactionSynchronizationManager.clearSynchronization();
            TransactionSynchronizationManager.setActualTransactionActive(false);
        }
    }

    private BabyEventMapper writableMapper() {
        var mapper = mock(BabyEventMapper.class);
        when(mapper.countBabyInFamily(42L, 7L)).thenReturn(1);
        when(mapper.insert(any(BabyEvent.class))).thenAnswer(invocation -> {
            BabyEvent event = invocation.getArgument(0);
            event.setId(99L);
            return 1;
        });
        when(mapper.findById(42L, 99L)).thenAnswer(invocation -> {
            var event = new BabyEvent();
            event.setId(99L);
            return event;
        });
        return mapper;
    }
}
