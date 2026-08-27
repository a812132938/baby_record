package com.babyrecord.mapper;

import com.babyrecord.dto.BabySummary;
import com.babyrecord.model.AiAggregateRow;
import com.babyrecord.model.AiConversationRow;
import com.babyrecord.model.AiMessageRow;
import com.babyrecord.model.AiRecentEventRow;
import com.babyrecord.model.AiSnapshotRow;
import com.babyrecord.model.AiStoolBreakdownRow;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.time.LocalDateTime;
import java.util.List;

@Mapper
public interface AiConversationMapper {
    String CONVERSATION_SELECT = """
        SELECT id, family_id, baby_id, created_by, client_request_id, title, status, model,
               last_error_code, data_processing_accepted_at, archived_at, created_at, updated_at
          FROM ai_conversation
        """;

    String MESSAGE_SELECT = """
        SELECT m.id, m.conversation_id, m.family_id, m.baby_id, m.author_user_id,
               m.client_message_id, m.role, m.status, m.content, m.snapshot_id, m.error_code, m.search_used,
               u.nickname AS author_name, s.snapshot_at, m.created_at, m.updated_at
          FROM ai_message m
          LEFT JOIN app_user u ON u.id = m.author_user_id
          LEFT JOIN ai_snapshot s ON s.id = m.snapshot_id
        """;

    @Insert("""
        INSERT INTO ai_conversation
          (family_id, baby_id, created_by, client_request_id, title, status, model,
           data_processing_accepted_at, created_at, updated_at)
        VALUES
          (#{familyId}, #{babyId}, #{userId}, #{clientRequestId},
           CONCAT('宝宝记录分析 ', DATE_FORMAT(NOW(3), '%c月%e日')), 'ANALYZING', #{model},
           NOW(3), NOW(3), NOW(3))
        ON DUPLICATE KEY UPDATE id = LAST_INSERT_ID(id)
        """)
    int insertConversation(@Param("familyId") long familyId,
                           @Param("babyId") long babyId,
                           @Param("userId") long userId,
                           @Param("clientRequestId") String clientRequestId,
                           @Param("model") String model);

    @Select(CONVERSATION_SELECT + """
         WHERE family_id = #{familyId} AND baby_id = #{babyId}
           AND client_request_id = #{clientRequestId} AND archived_at IS NULL
         LIMIT 1
        """)
    AiConversationRow findByClientRequest(@Param("familyId") long familyId,
                                          @Param("babyId") long babyId,
                                          @Param("clientRequestId") String clientRequestId);

    @Select(CONVERSATION_SELECT + """
         WHERE id = #{conversationId} AND family_id = #{familyId} AND baby_id = #{babyId}
           AND archived_at IS NULL
         LIMIT 1
        """)
    AiConversationRow findConversation(@Param("familyId") long familyId,
                                       @Param("babyId") long babyId,
                                       @Param("conversationId") long conversationId);

    @Select(CONVERSATION_SELECT + """
         WHERE family_id = #{familyId} AND baby_id = #{babyId} AND archived_at IS NULL
         ORDER BY updated_at DESC, id DESC
         LIMIT 100
        """)
    List<AiConversationRow> listConversations(@Param("familyId") long familyId,
                                              @Param("babyId") long babyId);

    @Insert("""
        INSERT INTO ai_message
          (conversation_id, family_id, baby_id, author_user_id, client_message_id,
           role, status, content, created_at, updated_at)
        VALUES
          (#{conversationId}, #{familyId}, #{babyId}, NULL, #{requestId},
           'ASSISTANT', 'PENDING', NULL, NOW(3), NOW(3))
        ON DUPLICATE KEY UPDATE id = LAST_INSERT_ID(id)
        """)
    int insertAssistantPlaceholder(@Param("conversationId") long conversationId,
                                   @Param("familyId") long familyId,
                                   @Param("babyId") long babyId,
                                   @Param("requestId") String requestId);

    @Insert("""
        INSERT INTO ai_message
          (conversation_id, family_id, baby_id, author_user_id, client_message_id,
           role, status, content, created_at, updated_at)
        VALUES
          (#{conversationId}, #{familyId}, #{babyId}, #{userId}, #{clientMessageId},
           'USER', 'COMPLETED', #{content}, NOW(3), NOW(3))
        ON DUPLICATE KEY UPDATE id = LAST_INSERT_ID(id)
        """)
    int insertUserMessage(@Param("conversationId") long conversationId,
                          @Param("familyId") long familyId,
                          @Param("babyId") long babyId,
                          @Param("userId") long userId,
                          @Param("clientMessageId") String clientMessageId,
                          @Param("content") String content);

    @Select(MESSAGE_SELECT + """
         WHERE m.conversation_id = #{conversationId} AND m.family_id = #{familyId}
           AND m.baby_id = #{babyId} AND m.role = #{role} AND m.client_message_id = #{requestId}
         LIMIT 1
        """)
    AiMessageRow findMessageByRequest(@Param("conversationId") long conversationId,
                                      @Param("familyId") long familyId,
                                      @Param("babyId") long babyId,
                                      @Param("role") String role,
                                      @Param("requestId") String requestId);

    @Select(MESSAGE_SELECT + """
         WHERE m.id = #{messageId} AND m.conversation_id = #{conversationId}
           AND m.family_id = #{familyId} AND m.baby_id = #{babyId}
           AND m.role = 'ASSISTANT'
         LIMIT 1
        """)
    AiMessageRow findAssistantMessage(@Param("messageId") long messageId,
                                      @Param("conversationId") long conversationId,
                                      @Param("familyId") long familyId,
                                      @Param("babyId") long babyId);

    @Select(MESSAGE_SELECT + """
         WHERE m.conversation_id = #{conversationId} AND m.family_id = #{familyId}
           AND m.baby_id = #{babyId}
         ORDER BY m.id ASC
        """)
    List<AiMessageRow> listMessages(@Param("conversationId") long conversationId,
                                    @Param("familyId") long familyId,
                                    @Param("babyId") long babyId);

    @Select(MESSAGE_SELECT + """
         WHERE m.conversation_id = #{conversationId} AND m.family_id = #{familyId}
           AND m.baby_id = #{babyId} AND m.status = 'COMPLETED'
         ORDER BY m.id DESC LIMIT 24
        """)
    List<AiMessageRow> findRecentCompletedMessages(@Param("conversationId") long conversationId,
                                                   @Param("familyId") long familyId,
                                                   @Param("babyId") long babyId);

    @Select(MESSAGE_SELECT + """
         WHERE m.conversation_id = #{conversationId} AND m.family_id = #{familyId}
           AND m.baby_id = #{babyId} AND m.role = 'ASSISTANT' AND m.status = 'COMPLETED'
         ORDER BY m.id ASC LIMIT 1
        """)
    AiMessageRow findInitialAnalysis(@Param("conversationId") long conversationId,
                                     @Param("familyId") long familyId,
                                     @Param("babyId") long babyId);

    @Update("""
        UPDATE ai_conversation
           SET status = 'RESPONDING', last_error_code = NULL, updated_at = NOW(3)
         WHERE id = #{conversationId} AND family_id = #{familyId} AND baby_id = #{babyId}
           AND archived_at IS NULL AND status = 'READY'
        """)
    int beginResponse(@Param("conversationId") long conversationId,
                      @Param("familyId") long familyId,
                      @Param("babyId") long babyId);

    @Update("""
        UPDATE ai_conversation
           SET status = #{nextStatus}, last_error_code = NULL, updated_at = NOW(3)
         WHERE id = #{conversationId} AND family_id = #{familyId} AND baby_id = #{babyId}
           AND archived_at IS NULL AND status = 'FAILED'
        """)
    int beginRetry(@Param("conversationId") long conversationId,
                   @Param("familyId") long familyId,
                   @Param("babyId") long babyId,
                   @Param("nextStatus") String nextStatus);

    @Select(MESSAGE_SELECT + """
         WHERE m.conversation_id = #{conversationId} AND m.family_id = #{familyId}
           AND m.baby_id = #{babyId} AND m.role = 'ASSISTANT' AND m.status = 'FAILED'
         ORDER BY m.id DESC LIMIT 1
        """)
    AiMessageRow findLastFailedAssistant(@Param("conversationId") long conversationId,
                                         @Param("familyId") long familyId,
                                         @Param("babyId") long babyId);

    @Update("""
        UPDATE ai_message
           SET status = 'PENDING', content = NULL, snapshot_id = NULL, error_code = NULL,
               search_used = 0, updated_at = NOW(3)
         WHERE id = #{messageId} AND conversation_id = #{conversationId}
           AND family_id = #{familyId} AND baby_id = #{babyId} AND role = 'ASSISTANT' AND status = 'FAILED'
        """)
    int resetFailedAssistant(@Param("messageId") long messageId,
                             @Param("conversationId") long conversationId,
                             @Param("familyId") long familyId,
                             @Param("babyId") long babyId);

    @Insert("""
        INSERT INTO ai_snapshot
          (conversation_id, family_id, baby_id, snapshot_at, range_start, range_end,
           source_event_count, prompt_version, dashboard, prompt_text, created_at)
        VALUES
          (#{conversationId}, #{familyId}, #{babyId}, #{snapshotAt}, #{rangeStart}, #{rangeEnd},
           #{sourceEventCount}, #{promptVersion}, #{dashboard}, #{promptText}, NOW(3))
        """)
    int insertSnapshot(@Param("conversationId") long conversationId,
                       @Param("familyId") long familyId,
                       @Param("babyId") long babyId,
                       @Param("snapshotAt") LocalDateTime snapshotAt,
                       @Param("rangeStart") LocalDateTime rangeStart,
                       @Param("rangeEnd") LocalDateTime rangeEnd,
                       @Param("sourceEventCount") int sourceEventCount,
                       @Param("promptVersion") String promptVersion,
                       @Param("dashboard") String dashboard,
                       @Param("promptText") String promptText);

    @Select("""
        SELECT id, conversation_id, family_id, baby_id, snapshot_at, range_start, range_end,
               source_event_count, prompt_version, dashboard, prompt_text, created_at
          FROM ai_snapshot
         WHERE conversation_id = #{conversationId} AND family_id = #{familyId} AND baby_id = #{babyId}
         ORDER BY id DESC LIMIT 1
        """)
    AiSnapshotRow findLatestSnapshot(@Param("conversationId") long conversationId,
                                     @Param("familyId") long familyId,
                                     @Param("babyId") long babyId);

    @Select("""
        SELECT id, conversation_id, family_id, baby_id, snapshot_at, range_start, range_end,
               source_event_count, prompt_version, dashboard, prompt_text, created_at
          FROM ai_snapshot
         WHERE id = #{snapshotId} AND conversation_id = #{conversationId}
           AND family_id = #{familyId} AND baby_id = #{babyId}
         LIMIT 1
        """)
    AiSnapshotRow findSnapshot(@Param("snapshotId") long snapshotId,
                               @Param("conversationId") long conversationId,
                               @Param("familyId") long familyId,
                               @Param("babyId") long babyId);

    @Update("""
        UPDATE ai_message
           SET snapshot_id = #{snapshotId}, updated_at = NOW(3)
         WHERE id = #{messageId} AND conversation_id = #{conversationId}
           AND family_id = #{familyId} AND baby_id = #{babyId}
           AND role = 'ASSISTANT' AND status = 'PENDING'
        """)
    int attachSnapshot(@Param("messageId") long messageId,
                       @Param("snapshotId") long snapshotId,
                       @Param("conversationId") long conversationId,
                       @Param("familyId") long familyId,
                       @Param("babyId") long babyId);

    @Update("""
        UPDATE ai_message
           SET status = 'COMPLETED', content = #{content}, error_code = NULL,
               search_used = #{searchUsed}, updated_at = NOW(3)
         WHERE id = #{messageId} AND conversation_id = #{conversationId}
           AND family_id = #{familyId} AND baby_id = #{babyId}
           AND role = 'ASSISTANT' AND status = 'PENDING' AND snapshot_id = #{snapshotId}
        """)
    int completeAssistant(@Param("messageId") long messageId,
                          @Param("snapshotId") long snapshotId,
                          @Param("conversationId") long conversationId,
                          @Param("familyId") long familyId,
                          @Param("babyId") long babyId,
                          @Param("content") String content,
                          @Param("searchUsed") boolean searchUsed);

    @Update("""
        UPDATE ai_conversation
           SET status = 'READY', last_error_code = NULL, updated_at = NOW(3)
         WHERE id = #{conversationId} AND family_id = #{familyId} AND baby_id = #{babyId}
           AND archived_at IS NULL AND status IN ('ANALYZING', 'RESPONDING')
        """)
    int completeConversation(@Param("conversationId") long conversationId,
                             @Param("familyId") long familyId,
                             @Param("babyId") long babyId);

    @Update("""
        UPDATE ai_message
           SET status = 'FAILED', content = NULL, error_code = #{errorCode}, updated_at = NOW(3)
         WHERE id = #{messageId} AND conversation_id = #{conversationId}
           AND family_id = #{familyId} AND baby_id = #{babyId}
           AND role = 'ASSISTANT' AND status = 'PENDING'
        """)
    int failAssistant(@Param("messageId") long messageId,
                      @Param("conversationId") long conversationId,
                      @Param("familyId") long familyId,
                      @Param("babyId") long babyId,
                      @Param("errorCode") String errorCode);

    @Update("""
        UPDATE ai_conversation
           SET status = 'FAILED', last_error_code = #{errorCode}, updated_at = NOW(3)
         WHERE id = #{conversationId} AND family_id = #{familyId} AND baby_id = #{babyId}
           AND archived_at IS NULL AND status IN ('ANALYZING', 'RESPONDING')
        """)
    int failConversation(@Param("conversationId") long conversationId,
                         @Param("familyId") long familyId,
                         @Param("babyId") long babyId,
                         @Param("errorCode") String errorCode);

    @Update("""
        UPDATE ai_conversation
           SET archived_at = NOW(3), updated_at = NOW(3)
         WHERE id = #{conversationId} AND family_id = #{familyId} AND baby_id = #{babyId}
           AND archived_at IS NULL
        """)
    int archiveConversation(@Param("conversationId") long conversationId,
                            @Param("familyId") long familyId,
                            @Param("babyId") long babyId);

    @Select("""
        SELECT COUNT(*)
          FROM ai_message m
          JOIN ai_conversation c ON c.id = m.conversation_id
         WHERE m.author_user_id = #{userId} AND m.family_id = #{familyId} AND m.baby_id = #{babyId}
           AND c.family_id = #{familyId} AND c.baby_id = #{babyId}
           AND m.role = 'USER' AND m.created_at >= DATE_SUB(NOW(), INTERVAL 1 HOUR)
        """)
    int countUserQuestionsLastHour(@Param("userId") long userId,
                                   @Param("familyId") long familyId,
                                   @Param("babyId") long babyId);

    @Select("""
        SELECT
          (SELECT COUNT(*) FROM ai_conversation c
            WHERE c.created_by = #{userId} AND c.family_id = #{familyId} AND c.baby_id = #{babyId}
              AND c.created_at >= DATE_SUB(NOW(), INTERVAL 1 HOUR))
          +
          (SELECT COUNT(*) FROM ai_message m
            WHERE m.author_user_id = #{userId} AND m.family_id = #{familyId} AND m.baby_id = #{babyId}
              AND m.role = 'USER' AND m.created_at >= DATE_SUB(NOW(), INTERVAL 1 HOUR))
        """)
    int countUserRequestsLastHour(@Param("userId") long userId,
                                  @Param("familyId") long familyId,
                                  @Param("babyId") long babyId);

    @Select("""
        SELECT COUNT(*)
          FROM ai_snapshot s
          JOIN ai_conversation c ON c.id = s.conversation_id
         WHERE s.family_id = #{familyId} AND s.baby_id = #{babyId}
           AND c.family_id = #{familyId} AND c.baby_id = #{babyId}
           AND s.created_at >= DATE_SUB(NOW(), INTERVAL 1 DAY)
        """)
    int countFamilySnapshotsLastDay(@Param("familyId") long familyId, @Param("babyId") long babyId);

    @Select("""
        SELECT
          (SELECT COUNT(*) FROM ai_conversation c
            WHERE c.family_id = #{familyId} AND c.baby_id = #{babyId}
              AND c.created_at >= DATE_SUB(NOW(), INTERVAL 1 DAY))
          +
          (SELECT COUNT(*) FROM ai_message m
            WHERE m.family_id = #{familyId} AND m.baby_id = #{babyId}
              AND m.role = 'USER' AND m.created_at >= DATE_SUB(NOW(), INTERVAL 1 DAY))
        """)
    int countFamilyRequestsLastDay(@Param("familyId") long familyId, @Param("babyId") long babyId);

    @Select("""
        SELECT COUNT(*) FROM ai_message
         WHERE conversation_id = #{conversationId} AND family_id = #{familyId} AND baby_id = #{babyId}
           AND role = 'USER'
        """)
    int countConversationQuestions(@Param("conversationId") long conversationId,
                                   @Param("familyId") long familyId,
                                   @Param("babyId") long babyId);

    @Select(MESSAGE_SELECT + """
         JOIN ai_conversation c ON c.id = m.conversation_id
         WHERE c.family_id = m.family_id AND c.baby_id = m.baby_id
           AND c.archived_at IS NULL AND c.status IN ('ANALYZING', 'RESPONDING')
           AND c.updated_at < DATE_SUB(NOW(), INTERVAL 3 MINUTE)
           AND m.role = 'ASSISTANT' AND m.status = 'PENDING'
         ORDER BY m.id ASC LIMIT 20
        """)
    List<AiMessageRow> findStalePendingMessages();

    @Update("""
        UPDATE ai_conversation
           SET updated_at = NOW(3)
         WHERE id = #{conversationId} AND family_id = #{familyId} AND baby_id = #{babyId}
           AND archived_at IS NULL AND status IN ('ANALYZING', 'RESPONDING')
           AND updated_at < DATE_SUB(NOW(), INTERVAL 3 MINUTE)
        """)
    int claimStaleConversation(@Param("conversationId") long conversationId,
                               @Param("familyId") long familyId,
                               @Param("babyId") long babyId);

    @Select("""
        SELECT b.id, b.nickname, b.birthday, b.gender, b.birth_weight_grams
          FROM baby b
         WHERE b.id = #{babyId} AND b.family_id = #{familyId}
         LIMIT 1
        """)
    BabySummary findBabyProfile(@Param("familyId") long familyId, @Param("babyId") long babyId);

    @Select("""
        SELECT NOW(3)
          FROM baby
         WHERE id = #{babyId} AND family_id = #{familyId}
         LIMIT 1
        """)
    LocalDateTime databaseNow(@Param("familyId") long familyId, @Param("babyId") long babyId);

    @Select("""
        SELECT MIN(e.start_time) AS range_start,
               COUNT(e.id) AS source_event_count,
               SUM(CASE WHEN e.event_type IN ('FEED','DIRECT_BREASTFEED','BOTTLE_BREAST_MILK','FORMULA_FEED','PUMPING') THEN 1 ELSE 0 END) AS total_records,
               COALESCE(SUM(e.event_type = 'DIRECT_BREASTFEED'), 0) AS direct_breastfeed_count,
               COALESCE(SUM(CASE WHEN e.event_type = 'DIRECT_BREASTFEED' THEN
                 COALESCE(CAST(JSON_UNQUOTE(JSON_EXTRACT(e.event_data, '$.leftSeconds')) AS UNSIGNED), 0) +
                 COALESCE(CAST(JSON_UNQUOTE(JSON_EXTRACT(e.event_data, '$.rightSeconds')) AS UNSIGNED), 0) ELSE 0 END), 0) AS direct_breastfeed_seconds,
               COALESCE(SUM(e.event_type = 'BOTTLE_BREAST_MILK'), 0) AS bottle_breast_milk_count,
               COALESCE(SUM(CASE WHEN e.event_type = 'BOTTLE_BREAST_MILK' THEN e.amount_ml ELSE 0 END), 0) AS bottle_breast_milk_ml,
               COALESCE(SUM(e.event_type = 'FORMULA_FEED'), 0) AS formula_feed_count,
               COALESCE(SUM(CASE WHEN e.event_type = 'FORMULA_FEED' THEN e.amount_ml ELSE 0 END), 0) AS formula_feed_ml,
               COALESCE(SUM(e.event_type = 'FEED'), 0) AS unclassified_bottle_count,
               COALESCE(SUM(CASE WHEN e.event_type = 'FEED' THEN e.amount_ml ELSE 0 END), 0) AS unclassified_bottle_ml,
               COALESCE(SUM(e.event_type = 'PUMPING'), 0) AS pumping_count,
               COALESCE(SUM(CASE WHEN e.event_type = 'PUMPING' THEN e.amount_ml ELSE 0 END), 0) AS pumping_ml,
               COALESCE(SUM(CASE WHEN e.event_type = 'PUMPING' THEN
                 COALESCE(CAST(JSON_UNQUOTE(JSON_EXTRACT(e.event_data, '$.durationSeconds')) AS UNSIGNED), 0) ELSE 0 END), 0) AS pumping_seconds,
               COALESCE(SUM(e.event_type = 'SLEEP' AND e.end_time IS NOT NULL), 0) AS completed_sleep_sessions,
               COALESCE(SUM(e.event_type = 'SLEEP' AND e.end_time IS NULL), 0) AS ongoing_sleep_sessions,
               COALESCE(SUM(CASE WHEN e.event_type = 'SLEEP' THEN GREATEST(0, TIMESTAMPDIFF(MINUTE, e.start_time, LEAST(COALESCE(e.end_time, #{cutoff}), #{cutoff}))) ELSE 0 END), 0) AS sleep_minutes,
               COALESCE(SUM(CASE WHEN e.event_type = 'SLEEP' AND e.end_time IS NOT NULL THEN GREATEST(0, TIMESTAMPDIFF(MINUTE, e.start_time, LEAST(e.end_time, #{cutoff}))) ELSE 0 END), 0) AS completed_sleep_minutes,
               COALESCE(MAX(CASE WHEN e.event_type = 'SLEEP' THEN GREATEST(0, TIMESTAMPDIFF(MINUTE, e.start_time, LEAST(COALESCE(e.end_time, #{cutoff}), #{cutoff}))) ELSE 0 END), 0) AS longest_sleep_minutes,
               COALESCE(MAX(CASE WHEN e.event_type = 'SLEEP' AND e.end_time IS NULL THEN GREATEST(0, TIMESTAMPDIFF(MINUTE, e.start_time, #{cutoff})) ELSE 0 END), 0) AS current_sleep_minutes,
               COALESCE(SUM(e.event_type = 'POOP'), 0) AS stool_count
          FROM baby b
          LEFT JOIN baby_event e ON e.baby_id = b.id
           AND e.start_time <= #{cutoff}
           AND e.event_type IN ('FEED','DIRECT_BREASTFEED','BOTTLE_BREAST_MILK','FORMULA_FEED','PUMPING','SLEEP','POOP')
         WHERE b.id = #{babyId} AND b.family_id = #{familyId}
         GROUP BY b.id
        """)
    AiAggregateRow aggregate(@Param("familyId") long familyId,
                             @Param("babyId") long babyId,
                             @Param("cutoff") LocalDateTime cutoff);

    @Select("""
        SELECT e.id, e.event_type, e.start_time, e.end_time, e.amount_ml, e.event_data
          FROM baby b JOIN baby_event e ON e.baby_id = b.id
         WHERE b.id = #{babyId} AND b.family_id = #{familyId}
           AND e.event_type IN ('FEED','DIRECT_BREASTFEED','BOTTLE_BREAST_MILK','FORMULA_FEED','PUMPING')
           AND e.start_time <= #{cutoff}
         ORDER BY e.start_time ASC, e.id ASC
        """)
    List<AiRecentEventRow> feedingEvents(@Param("familyId") long familyId,
                                         @Param("babyId") long babyId,
                                         @Param("cutoff") LocalDateTime cutoff);

    @Select("""
        SELECT e.id, e.event_type, e.start_time, e.end_time, e.amount_ml, e.event_data
          FROM baby b JOIN baby_event e ON e.baby_id = b.id
         WHERE b.id = #{babyId} AND b.family_id = #{familyId}
           AND e.event_type IN ('SLEEP','POOP')
           AND (
             e.event_type = 'SLEEP' AND e.start_time <= #{cutoff}
               AND COALESCE(e.end_time, #{cutoff}) >= DATE_SUB(#{cutoff}, INTERVAL 48 HOUR)
             OR e.event_type = 'POOP'
               AND e.start_time >= DATE_SUB(#{cutoff}, INTERVAL 48 HOUR) AND e.start_time <= #{cutoff}
           )
         ORDER BY e.start_time DESC LIMIT 200
        """)
    List<AiRecentEventRow> recentEvents(@Param("familyId") long familyId,
                                        @Param("babyId") long babyId,
                                        @Param("cutoff") LocalDateTime cutoff);

    @Select("""
        SELECT CASE
                 WHEN JSON_UNQUOTE(JSON_EXTRACT(e.event_data, '$.color')) IN ('黄色','黄绿色','绿色','棕色')
                   THEN JSON_UNQUOTE(JSON_EXTRACT(e.event_data, '$.color'))
                 WHEN JSON_EXTRACT(e.event_data, '$.color') IS NULL OR NULLIF(JSON_UNQUOTE(JSON_EXTRACT(e.event_data, '$.color')), '') IS NULL
                   THEN '未记录'
                 ELSE '其他'
               END AS value, COUNT(*) AS count
          FROM baby b JOIN baby_event e ON e.baby_id = b.id
         WHERE b.id = #{babyId} AND b.family_id = #{familyId} AND e.event_type = 'POOP' AND e.start_time <= #{cutoff}
         GROUP BY value ORDER BY count DESC LIMIT 20
        """)
    List<AiStoolBreakdownRow> stoolColors(@Param("familyId") long familyId, @Param("babyId") long babyId, @Param("cutoff") LocalDateTime cutoff);

    @Select("""
        SELECT CASE
                 WHEN JSON_UNQUOTE(JSON_EXTRACT(e.event_data, '$.texture')) IN ('奶瓣','糊状','稀','水样')
                   THEN JSON_UNQUOTE(JSON_EXTRACT(e.event_data, '$.texture'))
                 WHEN JSON_EXTRACT(e.event_data, '$.texture') IS NULL OR NULLIF(JSON_UNQUOTE(JSON_EXTRACT(e.event_data, '$.texture')), '') IS NULL
                   THEN '未记录'
                 ELSE '其他'
               END AS value, COUNT(*) AS count
          FROM baby b JOIN baby_event e ON e.baby_id = b.id
         WHERE b.id = #{babyId} AND b.family_id = #{familyId} AND e.event_type = 'POOP' AND e.start_time <= #{cutoff}
         GROUP BY value ORDER BY count DESC LIMIT 20
        """)
    List<AiStoolBreakdownRow> stoolTextures(@Param("familyId") long familyId, @Param("babyId") long babyId, @Param("cutoff") LocalDateTime cutoff);

    @Select("""
        SELECT CASE
                 WHEN JSON_UNQUOTE(JSON_EXTRACT(e.event_data, '$.amount')) IN ('少','中','多')
                   THEN JSON_UNQUOTE(JSON_EXTRACT(e.event_data, '$.amount'))
                 WHEN JSON_EXTRACT(e.event_data, '$.amount') IS NULL OR NULLIF(JSON_UNQUOTE(JSON_EXTRACT(e.event_data, '$.amount')), '') IS NULL
                   THEN '未记录'
                 ELSE '其他'
               END AS value, COUNT(*) AS count
          FROM baby b JOIN baby_event e ON e.baby_id = b.id
         WHERE b.id = #{babyId} AND b.family_id = #{familyId} AND e.event_type = 'POOP' AND e.start_time <= #{cutoff}
         GROUP BY value ORDER BY count DESC LIMIT 20
        """)
    List<AiStoolBreakdownRow> stoolAmounts(@Param("familyId") long familyId, @Param("babyId") long babyId, @Param("cutoff") LocalDateTime cutoff);
}
