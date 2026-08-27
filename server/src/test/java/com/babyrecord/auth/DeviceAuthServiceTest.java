package com.babyrecord.auth;

import com.babyrecord.dto.DeviceClaimRequest;
import com.babyrecord.dto.FamilyCreateRequest;
import org.junit.jupiter.api.Test;
import org.springframework.core.Ordered;
import org.springframework.http.HttpStatus;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.web.server.ResponseStatusException;

import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class DeviceAuthServiceTest {

    private static final String CREATION_KEY = "11223344-5566-4788-8abc-112233445566";

    private final DeviceSessionMapper mapper = mock(DeviceSessionMapper.class);
    private final FamilyCreationRecoveryCleaner recoveryCleaner = mock(FamilyCreationRecoveryCleaner.class);
    private final DeviceAuthService service = new DeviceAuthService(mapper, recoveryCleaner, 30, 30);

    private void allowDeviceClaimLock() {
        when(mapper.acquireDeviceClaimLock(anyString())).thenReturn(1);
    }

    @Test
    void creationRecoveryTtlMustBePositive() {
        assertThatThrownBy(() -> new DeviceAuthService(mapper, recoveryCleaner, 30, 0))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("creation-recovery-minutes");
    }

    @Test
    void meUsesTheDefaultBabyFromTheCurrentFamily() {
        var principal = new DeviceSessionPrincipal(3L, 5L, 7L, "妈妈", "MEMBER");
        when(mapper.findDefaultBabyIdByFamilyId(7L)).thenReturn(42L);

        var me = service.toMe(principal);

        assertThat(me.babyId()).isEqualTo(42L);
    }

    @Test
    void firstClaimantInAFamilyBecomesAdmin() {
        allowDeviceClaimLock();
        var request = new DeviceClaimRequest(
                "dynamic-code", "爸爸", "12345678-1234-1234-1234-123456789012", "手机"
        );
        when(mapper.findFamilyIdByInviteCode("DYNAMIC-CODE")).thenReturn(7L);
        when(mapper.findUserIdByDeviceId(request.deviceId())).thenReturn(null);
        doAnswer(invocation -> {
            invocation.<DeviceSessionMapper.NewUser>getArgument(0).setId(11L);
            return 1;
        }).when(mapper).insertUser(any());
        when(mapper.countFamilyMembers(7L)).thenReturn(0);
        when(mapper.findActiveByTokenHash(anyString()))
                .thenReturn(new DeviceSessionPrincipal(3L, 11L, 7L, "爸爸", "ADMIN"));
        when(mapper.findDefaultBabyIdByFamilyId(7L)).thenReturn(42L);

        var claimed = service.claim(request);

        assertThat(claimed.me().role()).isEqualTo("ADMIN");
        assertThat(claimed.maxAge()).isEqualTo(Duration.ofDays(30));
        verify(mapper).lockFamily(7L);
        verify(mapper).insertFamilyMember(7L, 11L, "ADMIN");
    }

    @Test
    void laterClaimantsInAFamilyBecomeMembers() {
        allowDeviceClaimLock();
        var request = new DeviceClaimRequest(
                "dynamic-code", "奶奶", "87654321-4321-4321-4321-210987654321", "平板"
        );
        when(mapper.findFamilyIdByInviteCode("DYNAMIC-CODE")).thenReturn(7L);
        when(mapper.findUserIdByDeviceId(request.deviceId())).thenReturn(null);
        doAnswer(invocation -> {
            invocation.<DeviceSessionMapper.NewUser>getArgument(0).setId(12L);
            return 1;
        }).when(mapper).insertUser(any());
        when(mapper.countFamilyMembers(7L)).thenReturn(1);
        when(mapper.findActiveByTokenHash(anyString()))
                .thenReturn(new DeviceSessionPrincipal(4L, 12L, 7L, "奶奶", "MEMBER"));
        when(mapper.findDefaultBabyIdByFamilyId(7L)).thenReturn(42L);

        var claimed = service.claim(request);

        assertThat(claimed.me().role()).isEqualTo("MEMBER");
        verify(mapper).insertFamilyMember(7L, 12L, "MEMBER");
    }

    @Test
    void repeatedClaimFromTheSameDeviceReusesUserAndRole() {
        allowDeviceClaimLock();
        var deviceUuid = "12345678-1234-1234-1234-123456789012";
        var request = new DeviceClaimRequest("dynamic-code", "爸爸", deviceUuid, "新设备名");
        var existing = new DeviceSessionPrincipal(3L, 11L, 7L, "爸爸", "ADMIN");
        when(mapper.findFamilyIdByInviteCode("DYNAMIC-CODE")).thenReturn(7L);
        when(mapper.findUserIdByDeviceId(deviceUuid)).thenReturn(11L);
        when(mapper.findFamilyRole(7L, 11L)).thenReturn("ADMIN");
        when(mapper.findActiveByTokenHash(anyString())).thenReturn(existing);
        when(mapper.findDefaultBabyIdByFamilyId(7L)).thenReturn(42L);

        var claimed = service.claim(request);

        assertThat(claimed.me().userId()).isEqualTo(11L);
        assertThat(claimed.me().role()).isEqualTo("ADMIN");
        verify(mapper, never()).insertUser(any());
        verify(mapper, never()).insertFamilyMember(eq(7L), eq(11L), anyString());
        verify(mapper).upsertTrustedDevice(eq(11L), eq(7L), eq(deviceUuid), eq("新设备名"), anyString(), any());
    }

    @Test
    void existingDeviceJoinsAnotherFamilyWithoutCreatingAnotherUser() {
        allowDeviceClaimLock();
        var deviceUuid = "12345678-1234-1234-1234-123456789012";
        var request = new DeviceClaimRequest("other-code", "爸爸", deviceUuid, "手机");
        when(mapper.findFamilyIdByInviteCode("OTHER-CODE")).thenReturn(9L);
        when(mapper.findUserIdByDeviceId(deviceUuid)).thenReturn(11L);
        when(mapper.findFamilyRole(9L, 11L)).thenReturn(null);
        when(mapper.countFamilyMembers(9L)).thenReturn(1);
        when(mapper.findActiveByTokenHash(anyString()))
                .thenReturn(new DeviceSessionPrincipal(3L, 11L, 9L, "爸爸", "MEMBER"));
        when(mapper.findDefaultBabyIdByFamilyId(9L)).thenReturn(84L);

        var claimed = service.claim(request);

        assertThat(claimed.me().userId()).isEqualTo(11L);
        assertThat(claimed.me().role()).isEqualTo("MEMBER");
        verify(mapper, never()).insertUser(any());
        verify(mapper).insertFamilyMember(9L, 11L, "MEMBER");
    }

    @Test
    void unavailableDeviceLockRejectsClaimBeforeCreatingIdentity() {
        var deviceUuid = "12345678-1234-1234-1234-123456789012";
        var request = new DeviceClaimRequest("dynamic-code", "爸爸", deviceUuid, "手机");
        when(mapper.acquireDeviceClaimLock(deviceUuid)).thenReturn(0);

        assertThatThrownBy(() -> service.claim(request))
                .isInstanceOfSatisfying(ResponseStatusException.class,
                        exception -> assertThat(exception.getStatusCode()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE));

        verify(mapper, never()).findFamilyIdByInviteCode(anyString());
        verify(mapper, never()).insertUser(any());
    }

    @Test
    void deviceLockIsNormalizedAndReleasedWhenClaimFailsOutsideTransactionProxy() {
        var deviceUuid = "ABCDEF12-1234-1234-1234-123456789ABC";
        var normalized = deviceUuid.toLowerCase();
        var request = new DeviceClaimRequest("invalid-code", "爸爸", deviceUuid, "手机");
        when(mapper.acquireDeviceClaimLock(normalized)).thenReturn(1);
        when(mapper.findFamilyIdByInviteCode("INVALID-CODE")).thenReturn(null);

        assertThatThrownBy(() -> service.claim(request))
                .isInstanceOf(ResponseStatusException.class);

        verify(mapper).releaseDeviceClaimLock(normalized);
        verify(mapper, never()).insertUser(any());
    }

    @Test
    void deviceLockReleaseIsDeferredUntilTransactionCompletes() {
        var deviceUuid = "12345678-1234-1234-1234-123456789012";
        var request = new DeviceClaimRequest("invalid-code", "爸爸", deviceUuid, "手机");
        allowDeviceClaimLock();
        when(mapper.findFamilyIdByInviteCode("INVALID-CODE")).thenReturn(null);
        TransactionSynchronization synchronization;

        TransactionSynchronizationManager.initSynchronization();
        try {
            assertThatThrownBy(() -> service.claim(request))
                    .isInstanceOf(ResponseStatusException.class);
            verify(mapper, never()).releaseDeviceClaimLock(deviceUuid);
            synchronization = TransactionSynchronizationManager.getSynchronizations().get(0);
        } finally {
            TransactionSynchronizationManager.clearSynchronization();
        }

        assertThat(synchronization.getOrder()).isEqualTo(Ordered.HIGHEST_PRECEDENCE);
        synchronization.afterCompletion(TransactionSynchronization.STATUS_ROLLED_BACK);
        verify(mapper).releaseDeviceClaimLock(deviceUuid);
    }

    @Test
    void creatingFamilyWritesAggregateInOrderAndReturnsDynamicAdminIdentity() {
        var deviceUuid = "ABCDEF12-1234-1234-1234-123456789ABC";
        var normalizedDeviceUuid = deviceUuid.toLowerCase();
        var request = new FamilyCreateRequest(
                " 小满之家 ", " 小满 ", LocalDate.of(2026, 8, 1),
                "GIRL", 3200, " 妈妈 ", CREATION_KEY, deviceUuid, "手机"
        );
        when(mapper.acquireFamilyCreationLock(anyString())).thenReturn(1);
        when(mapper.acquireDeviceClaimLock(normalizedDeviceUuid)).thenReturn(1);
        when(mapper.findFamilyCreationRecovery(anyString())).thenReturn(null);
        when(mapper.findUserIdByDeviceId(normalizedDeviceUuid)).thenReturn(null);
        doAnswer(invocation -> {
            invocation.<DeviceSessionMapper.NewFamily>getArgument(0).setId(21L);
            return 1;
        }).when(mapper).insertFamily(any());
        doAnswer(invocation -> {
            invocation.<DeviceSessionMapper.NewBaby>getArgument(0).setId(22L);
            return 1;
        }).when(mapper).insertBaby(any());
        doAnswer(invocation -> {
            invocation.<DeviceSessionMapper.NewUser>getArgument(0).setId(23L);
            return 1;
        }).when(mapper).insertUser(any());
        when(mapper.findActiveByTokenHash(anyString()))
                .thenReturn(new DeviceSessionPrincipal(24L, 23L, 21L, "妈妈", "ADMIN"));
        when(mapper.findDefaultBabyIdByFamilyId(21L)).thenReturn(22L);

        var created = service.createFamily(request);

        assertThat(created.me()).isEqualTo(new com.babyrecord.dto.MeResponse(
                24L, 23L, 21L, 22L, "妈妈", "ADMIN"
        ));
        var familyCaptor = org.mockito.ArgumentCaptor.forClass(DeviceSessionMapper.NewFamily.class);
        verify(mapper).insertFamily(familyCaptor.capture());
        assertThat(familyCaptor.getValue().getName()).isEqualTo("小满之家");
        assertThat(familyCaptor.getValue().getInviteCode()).matches("^[0-9A-F]{32}$");

        var babyCaptor = org.mockito.ArgumentCaptor.forClass(DeviceSessionMapper.NewBaby.class);
        verify(mapper).insertBaby(babyCaptor.capture());
        assertThat(babyCaptor.getValue().getFamilyId()).isEqualTo(21L);
        assertThat(babyCaptor.getValue().getNickname()).isEqualTo("小满");
        assertThat(babyCaptor.getValue().getBirthDate()).isEqualTo(LocalDate.of(2026, 8, 1));
        assertThat(babyCaptor.getValue().getGender()).isEqualTo("GIRL");
        assertThat(babyCaptor.getValue().getBirthWeightGrams()).isEqualTo(3200);

        var writeOrder = inOrder(mapper);
        writeOrder.verify(mapper).insertFamily(any());
        writeOrder.verify(mapper).insertBaby(any());
        writeOrder.verify(mapper).insertUser(any());
        writeOrder.verify(mapper).insertFamilyMember(21L, 23L, "ADMIN");
        writeOrder.verify(mapper).insertTrustedDevice(
                eq(23L), eq(21L), eq(normalizedDeviceUuid), eq("手机"), anyString(), any()
        );
        writeOrder.verify(mapper).insertFamilyCreationRecovery(
                anyString(), eq(normalizedDeviceUuid), eq(23L), eq(21L), any()
        );
        var recoveryHash = org.mockito.ArgumentCaptor.forClass(String.class);
        var recoveryExpiry = org.mockito.ArgumentCaptor.forClass(LocalDateTime.class);
        verify(mapper).insertFamilyCreationRecovery(
                recoveryHash.capture(), eq(normalizedDeviceUuid), eq(23L), eq(21L), recoveryExpiry.capture()
        );
        assertThat(recoveryHash.getValue()).matches("^[0-9a-f]{64}$").isNotEqualTo(CREATION_KEY);
        assertThat(recoveryExpiry.getValue())
                .isAfter(LocalDateTime.now().plusMinutes(29))
                .isBefore(LocalDateTime.now().plusMinutes(31));
        verify(mapper).releaseDeviceClaimLock(normalizedDeviceUuid);
        verify(mapper).releaseFamilyCreationLock(recoveryHash.getValue());
    }

    @Test
    void retryWithSameCreationKeyAndDeviceRotatesSessionWithoutCreatingAnotherAggregate() {
        var deviceUuid = "12345678-1234-1234-1234-123456789012";
        var request = new FamilyCreateRequest(
                "ignored family", "ignored baby", LocalDate.of(2026, 8, 1),
                "GIRL", 3200, "ignored nickname", CREATION_KEY, deviceUuid, "新设备名"
        );
        when(mapper.acquireFamilyCreationLock(anyString())).thenReturn(1);
        when(mapper.acquireDeviceClaimLock(deviceUuid)).thenReturn(1);
        when(mapper.findFamilyCreationRecovery(anyString())).thenReturn(
                new DeviceSessionMapper.FamilyCreationRecovery(
                        deviceUuid, 23L, 21L, LocalDateTime.now().plusMinutes(10)
                )
        );
        when(mapper.findActiveFamilyCreationRecovery(anyString(), eq(deviceUuid))).thenReturn(
                new DeviceSessionPrincipal(24L, 23L, 21L, "妈妈", "ADMIN")
        );
        when(mapper.findActiveByTokenHash(anyString())).thenReturn(
                new DeviceSessionPrincipal(24L, 23L, 21L, "妈妈", "ADMIN")
        );
        when(mapper.findDefaultBabyIdByFamilyId(21L)).thenReturn(22L);

        var recovered = service.createFamily(request);

        var cleanupOrder = inOrder(recoveryCleaner, mapper);
        cleanupOrder.verify(recoveryCleaner).deleteExpired();
        cleanupOrder.verify(mapper).acquireFamilyCreationLock(anyString());
        assertThat(recovered.me().familyId()).isEqualTo(21L);
        assertThat(recovered.me().userId()).isEqualTo(23L);
        assertThat(recovered.me().babyId()).isEqualTo(22L);
        assertThat(recovered.me().role()).isEqualTo("ADMIN");
        verify(mapper).upsertTrustedDevice(
                eq(23L), eq(21L), eq(deviceUuid), eq("新设备名"), anyString(), any()
        );
        verify(mapper, never()).insertFamily(any());
        verify(mapper, never()).insertBaby(any());
        verify(mapper, never()).insertUser(any());
        verify(mapper, never()).insertFamilyCreationRecovery(
                anyString(), anyString(), anyLong(), anyLong(), any()
        );
    }

    @Test
    void creationKeyCannotRecoverFromAnotherDevice() {
        var deviceUuid = "12345678-1234-1234-1234-123456789012";
        var request = new FamilyCreateRequest(
                "小满之家", "小满", LocalDate.of(2026, 8, 1), "GIRL", 3200, "妈妈",
                CREATION_KEY, deviceUuid, "手机"
        );
        when(mapper.acquireFamilyCreationLock(anyString())).thenReturn(1);
        when(mapper.acquireDeviceClaimLock(deviceUuid)).thenReturn(1);
        when(mapper.findFamilyCreationRecovery(anyString())).thenReturn(
                new DeviceSessionMapper.FamilyCreationRecovery(
                        "87654321-4321-4321-4321-210987654321", 23L, 21L,
                        LocalDateTime.now().plusMinutes(10)
                )
        );

        assertThatThrownBy(() -> service.createFamily(request))
                .isInstanceOfSatisfying(ResponseStatusException.class,
                        exception -> assertThat(exception.getStatusCode()).isEqualTo(HttpStatus.CONFLICT));

        verify(mapper, never()).findActiveFamilyCreationRecovery(anyString(), anyString());
        verify(mapper, never()).upsertTrustedDevice(
                anyLong(), anyLong(), anyString(), any(), anyString(), any()
        );
        verify(mapper, never()).insertFamily(any());
    }

    @Test
    void expiredCreationRecoveryCannotRotateTheTrustedDeviceToken() {
        var deviceUuid = "12345678-1234-1234-1234-123456789012";
        var request = new FamilyCreateRequest(
                "小满之家", "小满", LocalDate.of(2026, 8, 1), "GIRL", 3200, "妈妈",
                CREATION_KEY, deviceUuid, "手机"
        );
        when(mapper.acquireFamilyCreationLock(anyString())).thenReturn(1);
        when(mapper.acquireDeviceClaimLock(deviceUuid)).thenReturn(1);
        when(mapper.findFamilyCreationRecovery(anyString())).thenReturn(
                new DeviceSessionMapper.FamilyCreationRecovery(
                        deviceUuid, 23L, 21L, LocalDateTime.now().minusSeconds(1)
                )
        );
        when(mapper.findActiveFamilyCreationRecovery(anyString(), eq(deviceUuid))).thenReturn(null);

        assertThatThrownBy(() -> service.createFamily(request))
                .isInstanceOfSatisfying(ResponseStatusException.class,
                        exception -> assertThat(exception.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN));

        verify(mapper, never()).upsertTrustedDevice(
                anyLong(), anyLong(), anyString(), any(), anyString(), any()
        );
        verify(mapper, never()).findActiveFamilyCreationRecovery(anyString(), anyString());
        verify(mapper, never()).insertFamily(any());
    }

    @Test
    void confirmingCreationDeletesOnlyTheRecoveryOwnedByTheAuthenticatedIdentity() {
        var principal = new DeviceSessionPrincipal(24L, 23L, 21L, "妈妈", "ADMIN");

        service.confirmFamilyCreation(CREATION_KEY, principal);

        var hashCaptor = org.mockito.ArgumentCaptor.forClass(String.class);
        var order = inOrder(recoveryCleaner, mapper);
        order.verify(recoveryCleaner).deleteExpired();
        order.verify(mapper).deleteConfirmedFamilyCreationRecovery(
                hashCaptor.capture(), eq(24L), eq(23L), eq(21L)
        );
        assertThat(hashCaptor.getValue()).matches("^[0-9a-f]{64}$").isNotEqualTo(CREATION_KEY);
    }

    @Test
    void anotherAuthenticatedIdentityCannotDeleteTheCreationRecovery() {
        var otherPrincipal = new DeviceSessionPrincipal(99L, 88L, 77L, "其他人", "MEMBER");
        when(mapper.deleteConfirmedFamilyCreationRecovery(anyString(), eq(99L), eq(88L), eq(77L)))
                .thenReturn(0);

        service.confirmFamilyCreation(CREATION_KEY, otherPrincipal);

        verify(mapper).deleteConfirmedFamilyCreationRecovery(
                anyString(), eq(99L), eq(88L), eq(77L)
        );
        verify(mapper, never()).deleteConfirmedFamilyCreationRecovery(
                anyString(), eq(24L), eq(23L), eq(21L)
        );
    }

    @Test
    void existingDeviceCannotCreateOrMigrateAFamilyAndReleasesLock() {
        var deviceUuid = "12345678-1234-1234-1234-123456789012";
        var request = new FamilyCreateRequest(
                "小满之家", "小满", LocalDate.of(2026, 8, 1), "GIRL", 3200, "妈妈",
                CREATION_KEY, deviceUuid, "手机"
        );
        when(mapper.acquireFamilyCreationLock(anyString())).thenReturn(1);
        when(mapper.acquireDeviceClaimLock(deviceUuid)).thenReturn(1);
        when(mapper.findFamilyCreationRecovery(anyString())).thenReturn(null);
        when(mapper.findUserIdByDeviceId(deviceUuid)).thenReturn(23L);

        assertThatThrownBy(() -> service.createFamily(request))
                .isInstanceOfSatisfying(ResponseStatusException.class,
                        exception -> assertThat(exception.getStatusCode()).isEqualTo(HttpStatus.CONFLICT));

        verify(mapper, never()).insertFamily(any());
        verify(mapper, never()).insertTrustedDevice(
                anyLong(), anyLong(), anyString(), any(), anyString(), any()
        );
        verify(mapper).releaseDeviceClaimLock(deviceUuid);
    }

    @Test
    void createFamilyDeviceLockReleaseIsDeferredUntilTransactionCompletes() {
        var deviceUuid = "12345678-1234-1234-1234-123456789012";
        var request = new FamilyCreateRequest(
                "小满之家", "小满", LocalDate.of(2026, 8, 1), "GIRL", 3200, "妈妈",
                CREATION_KEY, deviceUuid, "手机"
        );
        when(mapper.acquireFamilyCreationLock(anyString())).thenReturn(1);
        when(mapper.acquireDeviceClaimLock(deviceUuid)).thenReturn(1);
        when(mapper.findFamilyCreationRecovery(anyString())).thenReturn(null);
        when(mapper.findUserIdByDeviceId(deviceUuid)).thenReturn(23L);
        TransactionSynchronization synchronization;

        TransactionSynchronizationManager.initSynchronization();
        try {
            assertThatThrownBy(() -> service.createFamily(request))
                    .isInstanceOf(ResponseStatusException.class);
            verify(mapper, never()).releaseDeviceClaimLock(deviceUuid);
            synchronization = TransactionSynchronizationManager.getSynchronizations().get(0);
        } finally {
            TransactionSynchronizationManager.clearSynchronization();
        }

        synchronization.afterCompletion(TransactionSynchronization.STATUS_ROLLED_BACK);
        verify(mapper).releaseDeviceClaimLock(deviceUuid);
        verify(mapper).releaseFamilyCreationLock(anyString());
    }
}
