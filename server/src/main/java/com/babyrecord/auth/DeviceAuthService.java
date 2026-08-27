package com.babyrecord.auth;

import com.babyrecord.dto.DeviceClaimRequest;
import com.babyrecord.dto.FamilyCreateRequest;
import com.babyrecord.dto.MeResponse;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.Ordered;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.web.server.ResponseStatusException;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.Base64;
import java.util.Locale;

@Service
public class DeviceAuthService {
    public static final String COOKIE_NAME = "br_device";
    private static final SecureRandom RANDOM = new SecureRandom();
    private final DeviceSessionMapper mapper;
    private final FamilyCreationRecoveryCleaner recoveryCleaner;
    private final Duration sessionTtl;
    private final Duration creationRecoveryTtl;

    public DeviceAuthService(DeviceSessionMapper mapper,
                             FamilyCreationRecoveryCleaner recoveryCleaner,
                             @Value("${app.auth.session-days}") long sessionDays,
                             @Value("${app.auth.creation-recovery-minutes}") long creationRecoveryMinutes) {
        if (sessionDays < 1) throw new IllegalArgumentException("app.auth.session-days must be positive");
        if (creationRecoveryMinutes < 1) {
            throw new IllegalArgumentException("app.auth.creation-recovery-minutes must be positive");
        }
        this.mapper = mapper;
        this.recoveryCleaner = recoveryCleaner;
        this.sessionTtl = Duration.ofDays(sessionDays);
        this.creationRecoveryTtl = Duration.ofMinutes(creationRecoveryMinutes);
    }

    public ResolvedSession resolve(String rawToken) {
        if (rawToken == null || rawToken.isBlank()) return null;
        var principal = mapper.findActiveByTokenHash(hash(rawToken));
        if (principal == null) return null;
        mapper.touch(principal.deviceId());
        return new ResolvedSession(rawToken, principal);
    }

    @Transactional
    public ClaimedSession claim(DeviceClaimRequest request) {
        var deviceId = request.deviceId().toLowerCase(Locale.ROOT);
        if (!Integer.valueOf(1).equals(mapper.acquireDeviceClaimLock(deviceId))) {
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "设备认领繁忙，请稍后重试");
        }

        var releaseAfterMethod = true;
        try {
            releaseAfterMethod = registerDeviceLockRelease(deviceId);
            var familyId = mapper.findFamilyIdByInviteCode(request.inviteCode().trim().toUpperCase());
            if (familyId == null) {
                throw new ResponseStatusException(HttpStatus.NOT_FOUND, "家庭邀请码无效");
            }
            mapper.lockFamily(familyId);

            var nickname = request.nickname().trim();
            var existingUserId = mapper.findUserIdByDeviceId(deviceId);
            long userId;
            if (existingUserId == null) {
                var user = new DeviceSessionMapper.NewUser(nickname);
                mapper.insertUser(user);
                userId = user.getId();
            } else {
                userId = existingUserId;
                mapper.updateUserNickname(userId, nickname);
            }

            if (mapper.findFamilyRole(familyId, userId) == null) {
                var role = mapper.countFamilyMembers(familyId) == 0 ? "ADMIN" : "MEMBER";
                mapper.insertFamilyMember(familyId, userId, role);
            }

            return establishSession(userId, familyId, deviceId, request.deviceName(), true);
        } finally {
            if (releaseAfterMethod) mapper.releaseDeviceClaimLock(deviceId);
        }
    }

    @Transactional
    public ClaimedSession createFamily(FamilyCreateRequest request) {
        recoveryCleaner.deleteExpired();
        var deviceId = request.deviceId().toLowerCase(Locale.ROOT);
        var recoveryKeyHash = hash(request.creationKey().toLowerCase(Locale.ROOT));
        if (!Integer.valueOf(1).equals(mapper.acquireFamilyCreationLock(recoveryKeyHash))) {
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "家庭创建繁忙，请稍后重试");
        }

        var deviceLockAcquired = false;
        var releaseAfterMethod = true;
        try {
            if (!Integer.valueOf(1).equals(mapper.acquireDeviceClaimLock(deviceId))) {
                throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "设备注册繁忙，请稍后重试");
            }
            deviceLockAcquired = true;
            releaseAfterMethod = registerFamilyCreationLockRelease(deviceId, recoveryKeyHash);

            var recovery = mapper.findFamilyCreationRecovery(recoveryKeyHash);
            if (recovery != null) {
                if (!deviceId.equals(recovery.deviceId())) {
                    throw new ResponseStatusException(HttpStatus.CONFLICT, "创建凭证已被其他设备使用");
                }
                if (!recovery.expiresAt().isAfter(LocalDateTime.now())) {
                    throw new ResponseStatusException(HttpStatus.FORBIDDEN, "创建恢复凭证已过期");
                }
                var activeRecovery = mapper.findActiveFamilyCreationRecovery(recoveryKeyHash, deviceId);
                if (activeRecovery == null) {
                    throw new ResponseStatusException(HttpStatus.FORBIDDEN, "原创建会话已失效，无法恢复");
                }
                return establishSession(
                        activeRecovery.userId(), activeRecovery.familyId(),
                        deviceId, request.deviceName(), true
                );
            }

            if (mapper.findUserIdByDeviceId(deviceId) != null) {
                throw new ResponseStatusException(HttpStatus.CONFLICT, "此设备已加入家庭");
            }

            var family = new DeviceSessionMapper.NewFamily(
                    request.familyName().trim(), newInviteCode()
            );
            mapper.insertFamily(family);

            var baby = new DeviceSessionMapper.NewBaby(
                    family.getId(), request.babyNickname().trim(), request.birthDate(),
                    request.gender(), request.birthWeightGrams()
            );
            mapper.insertBaby(baby);

            var user = new DeviceSessionMapper.NewUser(request.nickname().trim());
            mapper.insertUser(user);
            mapper.insertFamilyMember(family.getId(), user.getId(), "ADMIN");

            var session = establishSession(
                    user.getId(), family.getId(), deviceId, request.deviceName(), false
            );
            mapper.insertFamilyCreationRecovery(
                    recoveryKeyHash, deviceId, user.getId(), family.getId(),
                    LocalDateTime.now().plus(creationRecoveryTtl)
            );
            return session;
        } finally {
            if (releaseAfterMethod) {
                if (deviceLockAcquired) mapper.releaseDeviceClaimLock(deviceId);
                mapper.releaseFamilyCreationLock(recoveryKeyHash);
            }
        }
    }

    @Transactional
    public void confirmFamilyCreation(String creationKey, DeviceSessionPrincipal principal) {
        recoveryCleaner.deleteExpired();
        mapper.deleteConfirmedFamilyCreationRecovery(
                hash(creationKey.toLowerCase(Locale.ROOT)),
                principal.deviceId(), principal.userId(), principal.familyId()
        );
    }

    private boolean registerFamilyCreationLockRelease(String deviceId, String recoveryKeyHash) {
        if (!TransactionSynchronizationManager.isSynchronizationActive()) return true;

        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public int getOrder() {
                return Ordered.HIGHEST_PRECEDENCE;
            }

            @Override
            public void afterCompletion(int status) {
                mapper.releaseDeviceClaimLock(deviceId);
                mapper.releaseFamilyCreationLock(recoveryKeyHash);
            }
        });
        return false;
    }

    private ClaimedSession establishSession(long userId,
                                             long familyId,
                                             String deviceId,
                                             String deviceName,
                                             boolean replaceExisting) {
        var rawToken = newToken();
        var tokenHash = hash(rawToken);
        var expiresAt = LocalDateTime.now().plus(sessionTtl);
        if (replaceExisting) {
            mapper.upsertTrustedDevice(userId, familyId, deviceId, deviceName, tokenHash, expiresAt);
        } else {
            mapper.insertTrustedDevice(userId, familyId, deviceId, deviceName, tokenHash, expiresAt);
        }

        var principal = mapper.findActiveByTokenHash(tokenHash);
        return new ClaimedSession(rawToken, expiresAt, sessionTtl, toMe(principal));
    }

    private boolean registerDeviceLockRelease(String deviceId) {
        if (!TransactionSynchronizationManager.isSynchronizationActive()) return true;

        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public int getOrder() {
                // The named lock belongs to the transaction connection, so release it before MyBatis unbinds that session.
                return Ordered.HIGHEST_PRECEDENCE;
            }

            @Override
            public void afterCompletion(int status) {
                mapper.releaseDeviceClaimLock(deviceId);
            }
        });
        return false;
    }

    public void revoke(String rawToken) {
        if (rawToken != null && !rawToken.isBlank()) mapper.revokeByTokenHash(hash(rawToken));
    }

    public MeResponse toMe(DeviceSessionPrincipal principal) {
        var babyId = mapper.findDefaultBabyIdByFamilyId(principal.familyId());
        if (babyId == null) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "家庭尚未配置宝宝");
        }
        return new MeResponse(
                principal.deviceId(), principal.userId(), principal.familyId(), babyId,
                principal.nickname(), principal.role()
        );
    }

    private String newToken() {
        byte[] bytes = new byte[32];
        RANDOM.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    private String newInviteCode() {
        byte[] bytes = new byte[16];
        RANDOM.nextBytes(bytes);
        return toHex(bytes).toUpperCase(Locale.ROOT);
    }

    private String hash(String value) {
        try {
            var digest = MessageDigest.getInstance("SHA-256");
            return toHex(digest.digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    private String toHex(byte[] bytes) {
        var out = new StringBuilder(bytes.length * 2);
        for (byte b : bytes) out.append(String.format("%02x", b));
        return out.toString();
    }

    public record ResolvedSession(String rawToken, DeviceSessionPrincipal principal) {}
    public record ClaimedSession(String rawToken, LocalDateTime expiresAt, Duration maxAge, MeResponse me) {}
}
