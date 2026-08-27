package com.babyrecord.auth;

import com.babyrecord.dto.FamilyDeviceResponse;
import org.apache.ibatis.annotations.*;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

@Mapper
public interface DeviceSessionMapper {

    @Select("SELECT GET_LOCK(CONCAT('babyrecord:claim:', LOWER(#{deviceUuid})), @@innodb_lock_wait_timeout)")
    Integer acquireDeviceClaimLock(@Param("deviceUuid") String deviceUuid);

    @Select("SELECT RELEASE_LOCK(CONCAT('babyrecord:claim:', LOWER(#{deviceUuid})))")
    Integer releaseDeviceClaimLock(@Param("deviceUuid") String deviceUuid);

    @Select("SELECT GET_LOCK(CONCAT('babyrecord:create:', LEFT(#{recoveryKeyHash}, 40)), @@innodb_lock_wait_timeout)")
    Integer acquireFamilyCreationLock(@Param("recoveryKeyHash") String recoveryKeyHash);

    @Select("SELECT RELEASE_LOCK(CONCAT('babyrecord:create:', LEFT(#{recoveryKeyHash}, 40)))")
    Integer releaseFamilyCreationLock(@Param("recoveryKeyHash") String recoveryKeyHash);

    @Select("""
        SELECT td.id AS device_id,
               td.user_id,
               td.family_id,
               u.nickname,
               fm.role
          FROM trusted_device td
          JOIN app_user u ON u.id = td.user_id
          JOIN family_member fm ON fm.family_id = td.family_id AND fm.user_id = td.user_id
         WHERE td.refresh_token_hash = #{tokenHash}
           AND td.revoked_at IS NULL
           AND (td.expires_at IS NULL OR td.expires_at > NOW(3))
         LIMIT 1
        """)
    DeviceSessionPrincipal findActiveByTokenHash(@Param("tokenHash") String tokenHash);

    @Select("SELECT user_id FROM trusted_device WHERE device_id = #{deviceUuid} LIMIT 1")
    Long findUserIdByDeviceId(@Param("deviceUuid") String deviceUuid);

    @Select("""
        SELECT device_id, user_id, family_id, expires_at
          FROM family_creation_recovery
         WHERE recovery_key_hash = #{recoveryKeyHash}
         LIMIT 1
        """)
    FamilyCreationRecovery findFamilyCreationRecovery(@Param("recoveryKeyHash") String recoveryKeyHash);

    @Select("""
        SELECT td.id AS device_id,
               td.user_id,
               td.family_id,
               u.nickname,
               fm.role
          FROM family_creation_recovery fcr
          JOIN trusted_device td
            ON td.device_id = fcr.device_id
           AND td.user_id = fcr.user_id
           AND td.family_id = fcr.family_id
          JOIN app_user u ON u.id = td.user_id
          JOIN family_member fm ON fm.family_id = td.family_id AND fm.user_id = td.user_id
         WHERE fcr.recovery_key_hash = #{recoveryKeyHash}
           AND fcr.device_id = #{deviceUuid}
           AND fcr.expires_at > NOW(3)
           AND td.revoked_at IS NULL
           AND (td.expires_at IS NULL OR td.expires_at > NOW(3))
           AND fm.role = 'ADMIN'
         LIMIT 1
         FOR UPDATE
        """)
    DeviceSessionPrincipal findActiveFamilyCreationRecovery(
            @Param("recoveryKeyHash") String recoveryKeyHash,
            @Param("deviceUuid") String deviceUuid
    );

    @Select("SELECT id FROM family WHERE invite_code = #{inviteCode} LIMIT 1")
    Long findFamilyIdByInviteCode(@Param("inviteCode") String inviteCode);

    @Select("SELECT id FROM family WHERE id = #{familyId} FOR UPDATE")
    Long lockFamily(@Param("familyId") long familyId);

    @Select("SELECT COUNT(*) FROM family_member WHERE family_id = #{familyId}")
    int countFamilyMembers(@Param("familyId") long familyId);

    @Select("SELECT role FROM family_member WHERE family_id = #{familyId} AND user_id = #{userId} LIMIT 1")
    String findFamilyRole(@Param("familyId") long familyId, @Param("userId") long userId);

    @Select("SELECT invite_code FROM family WHERE id = #{familyId} LIMIT 1")
    String findInviteCodeByFamilyId(@Param("familyId") long familyId);

    @Select("SELECT id FROM baby WHERE family_id = #{familyId} ORDER BY id ASC LIMIT 1")
    Long findDefaultBabyIdByFamilyId(@Param("familyId") long familyId);

    @Select("""
        SELECT td.id,
               td.user_id,
               u.nickname,
               fm.role,
               COALESCE(NULLIF(TRIM(td.device_name), ''), '未命名设备') AS device_name,
               td.last_active_at,
               td.created_at,
               CASE WHEN td.revoked_at IS NULL THEN FALSE ELSE TRUE END AS revoked
          FROM trusted_device td
          JOIN app_user u ON u.id = td.user_id
          JOIN family_member fm ON fm.family_id = td.family_id AND fm.user_id = td.user_id
         WHERE td.family_id = #{familyId}
         ORDER BY td.revoked_at IS NULL DESC, td.last_active_at DESC
        """)
    List<FamilyDeviceResponse> findDevicesByFamilyId(@Param("familyId") long familyId);

    @Select("SELECT family_id FROM trusted_device WHERE id = #{deviceId} LIMIT 1")
    Long findDeviceFamilyId(@Param("deviceId") long deviceId);

    @Insert("INSERT INTO app_user (nickname, created_at) VALUES (#{nickname}, NOW(3))")
    @Options(useGeneratedKeys = true, keyProperty = "id")
    int insertUser(NewUser user);

    @Insert("INSERT INTO family (name, invite_code, created_at) VALUES (#{name}, #{inviteCode}, NOW(3))")
    @Options(useGeneratedKeys = true, keyProperty = "id")
    int insertFamily(NewFamily family);

    @Insert("""
        INSERT INTO baby
          (family_id, nickname, birthday, gender, birth_weight_grams, created_at, updated_at)
        VALUES
          (#{familyId}, #{nickname}, #{birthDate}, #{gender}, #{birthWeightGrams}, NOW(3), NOW(3))
        """)
    @Options(useGeneratedKeys = true, keyProperty = "id")
    int insertBaby(NewBaby baby);

    @Update("UPDATE app_user SET nickname = #{nickname} WHERE id = #{userId}")
    int updateUserNickname(@Param("userId") long userId, @Param("nickname") String nickname);

    @Insert("""
        INSERT INTO family_member (family_id, user_id, role, created_at)
        VALUES (#{familyId}, #{userId}, #{role}, NOW(3))
        """)
    int insertFamilyMember(@Param("familyId") long familyId,
                           @Param("userId") long userId,
                           @Param("role") String role);

    @Insert("""
        INSERT INTO trusted_device
          (user_id, family_id, device_id, device_name, refresh_token_hash, last_active_at, expires_at, revoked_at, created_at)
        VALUES
          (#{userId}, #{familyId}, #{deviceUuid}, #{deviceName}, #{tokenHash}, NOW(3), #{expiresAt}, NULL, NOW(3))
        ON DUPLICATE KEY UPDATE
          user_id = VALUES(user_id),
          family_id = VALUES(family_id),
          device_name = VALUES(device_name),
          refresh_token_hash = VALUES(refresh_token_hash),
          last_active_at = NOW(3),
          expires_at = VALUES(expires_at),
          revoked_at = NULL
        """)
    int upsertTrustedDevice(@Param("userId") long userId,
                            @Param("familyId") long familyId,
                            @Param("deviceUuid") String deviceUuid,
                            @Param("deviceName") String deviceName,
                            @Param("tokenHash") String tokenHash,
                            @Param("expiresAt") LocalDateTime expiresAt);

    @Insert("""
        INSERT INTO trusted_device
          (user_id, family_id, device_id, device_name, refresh_token_hash, last_active_at, expires_at, revoked_at, created_at)
        VALUES
          (#{userId}, #{familyId}, #{deviceUuid}, #{deviceName}, #{tokenHash}, NOW(3), #{expiresAt}, NULL, NOW(3))
        """)
    int insertTrustedDevice(@Param("userId") long userId,
                            @Param("familyId") long familyId,
                            @Param("deviceUuid") String deviceUuid,
                            @Param("deviceName") String deviceName,
                            @Param("tokenHash") String tokenHash,
                            @Param("expiresAt") LocalDateTime expiresAt);

    @Insert("""
        INSERT INTO family_creation_recovery
          (recovery_key_hash, device_id, user_id, family_id, created_at, expires_at)
        VALUES
          (#{recoveryKeyHash}, #{deviceUuid}, #{userId}, #{familyId}, NOW(3), #{expiresAt})
        """)
    int insertFamilyCreationRecovery(@Param("recoveryKeyHash") String recoveryKeyHash,
                                     @Param("deviceUuid") String deviceUuid,
                                     @Param("userId") long userId,
                                     @Param("familyId") long familyId,
                                     @Param("expiresAt") LocalDateTime expiresAt);

    @Delete("DELETE FROM family_creation_recovery WHERE expires_at <= NOW(3)")
    int deleteExpiredFamilyCreationRecoveries();

    @Delete("""
        DELETE fcr
          FROM family_creation_recovery fcr
          JOIN trusted_device td
            ON td.device_id = fcr.device_id
           AND td.user_id = fcr.user_id
           AND td.family_id = fcr.family_id
         WHERE fcr.recovery_key_hash = #{recoveryKeyHash}
           AND fcr.expires_at > NOW(3)
           AND td.id = #{trustedDeviceId}
           AND td.user_id = #{userId}
           AND td.family_id = #{familyId}
           AND td.revoked_at IS NULL
           AND (td.expires_at IS NULL OR td.expires_at > NOW(3))
        """)
    int deleteConfirmedFamilyCreationRecovery(@Param("recoveryKeyHash") String recoveryKeyHash,
                                               @Param("trustedDeviceId") long trustedDeviceId,
                                               @Param("userId") long userId,
                                               @Param("familyId") long familyId);

    @Update("UPDATE trusted_device SET last_active_at = NOW(3) WHERE id = #{deviceId}")
    int touch(@Param("deviceId") long deviceId);

    @Update("UPDATE trusted_device SET revoked_at = NOW(3) WHERE refresh_token_hash = #{tokenHash}")
    int revokeByTokenHash(@Param("tokenHash") String tokenHash);

    @Update("UPDATE trusted_device SET revoked_at = NOW(3) WHERE id = #{deviceId} AND family_id = #{familyId} AND revoked_at IS NULL")
    int revokeDevice(@Param("familyId") long familyId, @Param("deviceId") long deviceId);

    record FamilyCreationRecovery(
            String deviceId,
            long userId,
            long familyId,
            LocalDateTime expiresAt
    ) {}

    class NewUser {
        private Long id;
        private String nickname;
        public NewUser(String nickname) { this.nickname = nickname; }
        public Long getId() { return id; }
        public void setId(Long id) { this.id = id; }
        public String getNickname() { return nickname; }
        public void setNickname(String nickname) { this.nickname = nickname; }
    }

    class NewFamily {
        private Long id;
        private String name;
        private String inviteCode;
        public NewFamily(String name, String inviteCode) {
            this.name = name;
            this.inviteCode = inviteCode;
        }
        public Long getId() { return id; }
        public void setId(Long id) { this.id = id; }
        public String getName() { return name; }
        public void setName(String name) { this.name = name; }
        public String getInviteCode() { return inviteCode; }
        public void setInviteCode(String inviteCode) { this.inviteCode = inviteCode; }
    }

    class NewBaby {
        private Long id;
        private Long familyId;
        private String nickname;
        private LocalDate birthDate;
        private String gender;
        private Integer birthWeightGrams;
        public NewBaby(long familyId, String nickname, LocalDate birthDate,
                       String gender, int birthWeightGrams) {
            this.familyId = familyId;
            this.nickname = nickname;
            this.birthDate = birthDate;
            this.gender = gender;
            this.birthWeightGrams = birthWeightGrams;
        }
        public Long getId() { return id; }
        public void setId(Long id) { this.id = id; }
        public Long getFamilyId() { return familyId; }
        public void setFamilyId(Long familyId) { this.familyId = familyId; }
        public String getNickname() { return nickname; }
        public void setNickname(String nickname) { this.nickname = nickname; }
        public LocalDate getBirthDate() { return birthDate; }
        public void setBirthDate(LocalDate birthDate) { this.birthDate = birthDate; }
        public String getGender() { return gender; }
        public void setGender(String gender) { this.gender = gender; }
        public Integer getBirthWeightGrams() { return birthWeightGrams; }
        public void setBirthWeightGrams(Integer birthWeightGrams) { this.birthWeightGrams = birthWeightGrams; }
    }
}
