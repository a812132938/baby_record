package com.babyrecord.mapper;

import com.babyrecord.dto.BabySummary;
import com.babyrecord.dto.DailyStatsRow;
import com.babyrecord.model.BabyEvent;
import org.apache.ibatis.annotations.*;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

@Mapper
public interface BabyEventMapper {
    String EVENT_SELECT = """
        SELECT e.id, e.baby_id, e.operator_id, u.nickname AS operator_name,
               e.end_operator_id, end_user.nickname AS end_operator_name,
               e.client_event_id, e.event_type, e.start_time, e.end_time,
               e.amount_ml, e.event_data, e.created_at, e.updated_at
          FROM baby_event e
          JOIN app_user u ON u.id = e.operator_id
          LEFT JOIN app_user end_user ON end_user.id = e.end_operator_id
        """;

    @Select("SELECT COUNT(*) FROM baby WHERE id = #{babyId} AND family_id = #{familyId}")
    int countBabyInFamily(@Param("babyId") long babyId, @Param("familyId") long familyId);

    @Select("SELECT id FROM baby WHERE id = #{babyId} AND family_id = #{familyId} FOR UPDATE")
    Long lockBabyForSleep(@Param("babyId") long babyId, @Param("familyId") long familyId);

    @Select("SELECT id, nickname, birthday, gender, birth_weight_grams FROM baby WHERE id = #{babyId} LIMIT 1")
    BabySummary findBabySummary(@Param("babyId") long babyId);

    @Update("""
        UPDATE baby
           SET nickname = #{nickname},
               birthday = #{birthday},
               gender = #{gender},
               birth_weight_grams = #{birthWeightGrams},
               updated_at = NOW(3)
         WHERE id = #{babyId} AND family_id = #{familyId}
        """)
    int updateBaby(@Param("babyId") long babyId,
                   @Param("familyId") long familyId,
                   @Param("nickname") String nickname,
                   @Param("birthday") LocalDate birthday,
                   @Param("gender") String gender,
                   @Param("birthWeightGrams") int birthWeightGrams);

    @Insert("""
        INSERT INTO baby_event
          (baby_id, operator_id, client_event_id, event_type, start_time, end_time, amount_ml, event_data, created_at, updated_at)
        VALUES
          (#{babyId}, #{operatorId}, #{clientEventId}, #{eventType}, #{startTime}, #{endTime}, #{amountMl}, #{eventData}, NOW(3), NOW(3))
        ON DUPLICATE KEY UPDATE
          id = LAST_INSERT_ID(id)
        """)
    @Options(useGeneratedKeys = true, keyProperty = "id")
    int insert(BabyEvent event);

    @Update("""
        UPDATE baby_event
           SET end_time = #{endTime}, end_operator_id = #{operatorId}, updated_at = NOW(3)
         WHERE id = #{id} AND baby_id = #{babyId} AND event_type = 'SLEEP' AND end_time IS NULL
        """)
    int endSleep(@Param("babyId") long babyId,
                 @Param("id") long id,
                 @Param("endTime") LocalDateTime endTime,
                 @Param("operatorId") long operatorId);

    @Update("""
        UPDATE baby_event
           SET start_time = #{startTime},
               end_time = #{endTime},
               amount_ml = #{amountMl},
               event_data = #{eventData},
               updated_at = NOW(3)
         WHERE id = #{id} AND baby_id = #{babyId}
           AND (#{expectedUpdatedAt} IS NULL OR updated_at = #{expectedUpdatedAt})
        """)
    int updateEvent(@Param("babyId") long babyId,
                    @Param("id") long id,
                    @Param("startTime") LocalDateTime startTime,
                    @Param("endTime") LocalDateTime endTime,
                    @Param("amountMl") Integer amountMl,
                    @Param("eventData") String eventData,
                    @Param("expectedUpdatedAt") LocalDateTime expectedUpdatedAt);

    @Delete("""
        DELETE FROM baby_event
         WHERE id = #{id} AND baby_id = #{babyId}
           AND (#{expectedUpdatedAt} IS NULL OR updated_at = #{expectedUpdatedAt})
        """)
    int deleteEvent(@Param("babyId") long babyId,
                    @Param("id") long id,
                    @Param("expectedUpdatedAt") LocalDateTime expectedUpdatedAt);

    @Select(EVENT_SELECT + """
         WHERE e.baby_id = #{babyId} AND e.client_event_id = #{clientEventId}
         LIMIT 1
        """)
    BabyEvent findByClientEventId(@Param("babyId") long babyId, @Param("clientEventId") String clientEventId);

    @Select(EVENT_SELECT + """
         WHERE e.baby_id = #{babyId} AND e.id = #{id}
         LIMIT 1
        """)
    BabyEvent findById(@Param("babyId") long babyId, @Param("id") long id);

    @Select(EVENT_SELECT + """
         WHERE e.baby_id = #{babyId} AND e.event_type = #{eventType}
         ORDER BY e.start_time DESC LIMIT 1
        """)
    BabyEvent findLastByType(@Param("babyId") long babyId, @Param("eventType") String eventType);

    @Select(EVENT_SELECT + """
         WHERE e.baby_id = #{babyId}
           AND e.event_type IN ('FEED', 'DIRECT_BREASTFEED', 'BOTTLE_BREAST_MILK', 'FORMULA_FEED')
         ORDER BY e.start_time DESC LIMIT 1
        """)
    BabyEvent findLastFeeding(@Param("babyId") long babyId);

    @Select(EVENT_SELECT + """
         WHERE e.baby_id = #{babyId} AND e.event_type = 'SLEEP' AND e.end_time IS NULL
         ORDER BY e.start_time DESC LIMIT 1
        """)
    BabyEvent findActiveSleep(@Param("babyId") long babyId);

    @Select(EVENT_SELECT + """
         WHERE e.baby_id = #{babyId} AND e.event_type = 'SLEEP' AND e.end_time IS NULL
         ORDER BY e.start_time DESC LIMIT 1
         FOR UPDATE
        """)
    BabyEvent findActiveSleepForUpdate(@Param("babyId") long babyId);

    @Select("""
        SELECT amount_ml
          FROM baby_event
         WHERE baby_id = #{babyId}
           AND event_type IN ('FEED', 'BOTTLE_BREAST_MILK', 'FORMULA_FEED')
           AND amount_ml IS NOT NULL
           AND start_time >= DATE_SUB(NOW(), INTERVAL 3 DAY)
         GROUP BY amount_ml
         ORDER BY COUNT(*) DESC, MAX(start_time) DESC, amount_ml ASC
         LIMIT 5
        """)
    List<Integer> findPopularFeedAmounts(@Param("babyId") long babyId);

    @Select(EVENT_SELECT + """
         WHERE e.baby_id = #{babyId}
         ORDER BY e.start_time DESC
         LIMIT #{limit}
        """)
    List<BabyEvent> findTimeline(@Param("babyId") long babyId, @Param("limit") int limit);


    @Select(EVENT_SELECT + """
         WHERE e.baby_id = #{babyId}
           AND (
             (e.event_type = 'SLEEP' AND e.start_time < #{endTime} AND COALESCE(e.end_time, NOW()) >= #{startTime})
             OR
             (e.event_type <> 'SLEEP' AND e.start_time >= #{startTime} AND e.start_time < #{endTime})
           )
         ORDER BY e.start_time DESC
        """)
    List<BabyEvent> findEventsForRange(@Param("babyId") long babyId,
                                       @Param("startTime") LocalDateTime startTime,
                                       @Param("endTime") LocalDateTime endTime);

    @Select("""
        SELECT COUNT(*) FROM baby_event
         WHERE baby_id = #{babyId} AND event_type = #{eventType}
           AND start_time >= CURRENT_DATE()
        """)
    int countToday(@Param("babyId") long babyId, @Param("eventType") String eventType);

    @Select("""
        SELECT COUNT(*) FROM baby_event
         WHERE baby_id = #{babyId}
           AND event_type IN ('FEED', 'DIRECT_BREASTFEED', 'BOTTLE_BREAST_MILK', 'FORMULA_FEED')
           AND start_time >= CURRENT_DATE()
        """)
    int countFeedingsToday(@Param("babyId") long babyId);

    @Select("""
        SELECT COALESCE(SUM(amount_ml), 0) FROM baby_event
         WHERE baby_id = #{babyId}
           AND event_type IN ('FEED', 'BOTTLE_BREAST_MILK', 'FORMULA_FEED')
           AND start_time >= CURRENT_DATE()
        """)
    int sumMilkToday(@Param("babyId") long babyId);

    @Select("""
        SELECT COALESCE(SUM(amount_ml), 0) FROM baby_event
         WHERE baby_id = #{babyId} AND event_type = #{eventType}
           AND start_time >= CURRENT_DATE()
        """)
    int sumAmountToday(@Param("babyId") long babyId, @Param("eventType") String eventType);

    @Select("""
        SELECT COALESCE(SUM(
                 COALESCE(CAST(JSON_UNQUOTE(JSON_EXTRACT(event_data, '$.leftSeconds')) AS UNSIGNED), 0)
               + COALESCE(CAST(JSON_UNQUOTE(JSON_EXTRACT(event_data, '$.rightSeconds')) AS UNSIGNED), 0)
               ), 0)
          FROM baby_event
         WHERE baby_id = #{babyId} AND event_type = 'DIRECT_BREASTFEED'
           AND start_time >= CURRENT_DATE()
        """)
    int sumDirectBreastfeedSecondsToday(@Param("babyId") long babyId);

    @Select("""
        SELECT COALESCE(SUM(
                 COALESCE(CAST(JSON_UNQUOTE(JSON_EXTRACT(event_data, '$.durationSeconds')) AS UNSIGNED), 0)
               ), 0)
          FROM baby_event
         WHERE baby_id = #{babyId} AND event_type = 'PUMPING'
           AND start_time >= CURRENT_DATE()
        """)
    int sumPumpingSecondsToday(@Param("babyId") long babyId);

    @Select("""
        SELECT DATE_FORMAT(days.d, '%Y-%m-%d') AS date,
               COALESCE(SUM(CASE WHEN e.event_type IN ('FEED', 'BOTTLE_BREAST_MILK', 'FORMULA_FEED') AND e.start_time >= days.d AND e.start_time < DATE_ADD(days.d, INTERVAL 1 DAY)
                                 THEN e.amount_ml ELSE 0 END), 0) AS milk_ml,
               COALESCE(SUM(CASE WHEN e.event_type IN ('FEED', 'DIRECT_BREASTFEED', 'BOTTLE_BREAST_MILK', 'FORMULA_FEED') AND e.start_time >= days.d AND e.start_time < DATE_ADD(days.d, INTERVAL 1 DAY)
                                 THEN 1 ELSE 0 END), 0) AS feed_count,
               COALESCE(SUM(CASE WHEN e.event_type = 'DIRECT_BREASTFEED' AND e.start_time >= days.d AND e.start_time < DATE_ADD(days.d, INTERVAL 1 DAY)
                                 THEN 1 ELSE 0 END), 0) AS direct_breastfeed_count,
               FLOOR(COALESCE(SUM(CASE WHEN e.event_type = 'DIRECT_BREASTFEED' AND e.start_time >= days.d AND e.start_time < DATE_ADD(days.d, INTERVAL 1 DAY)
                                 THEN COALESCE(CAST(JSON_UNQUOTE(JSON_EXTRACT(e.event_data, '$.leftSeconds')) AS UNSIGNED), 0)
                                    + COALESCE(CAST(JSON_UNQUOTE(JSON_EXTRACT(e.event_data, '$.rightSeconds')) AS UNSIGNED), 0)
                                 ELSE 0 END), 0) / 60) AS direct_breastfeed_minutes,
               COALESCE(SUM(CASE WHEN e.event_type = 'BOTTLE_BREAST_MILK' AND e.start_time >= days.d AND e.start_time < DATE_ADD(days.d, INTERVAL 1 DAY)
                                 THEN 1 ELSE 0 END), 0) AS bottle_breast_milk_count,
               COALESCE(SUM(CASE WHEN e.event_type = 'BOTTLE_BREAST_MILK' AND e.start_time >= days.d AND e.start_time < DATE_ADD(days.d, INTERVAL 1 DAY)
                                 THEN e.amount_ml ELSE 0 END), 0) AS bottle_breast_milk_ml,
               COALESCE(SUM(CASE WHEN e.event_type = 'FORMULA_FEED' AND e.start_time >= days.d AND e.start_time < DATE_ADD(days.d, INTERVAL 1 DAY)
                                 THEN 1 ELSE 0 END), 0) AS formula_feed_count,
               COALESCE(SUM(CASE WHEN e.event_type = 'FORMULA_FEED' AND e.start_time >= days.d AND e.start_time < DATE_ADD(days.d, INTERVAL 1 DAY)
                                 THEN e.amount_ml ELSE 0 END), 0) AS formula_feed_ml,
               COALESCE(SUM(CASE WHEN e.event_type = 'PUMPING' AND e.start_time >= days.d AND e.start_time < DATE_ADD(days.d, INTERVAL 1 DAY)
                                 THEN 1 ELSE 0 END), 0) AS pumping_count,
               COALESCE(SUM(CASE WHEN e.event_type = 'PUMPING' AND e.start_time >= days.d AND e.start_time < DATE_ADD(days.d, INTERVAL 1 DAY)
                                 THEN e.amount_ml ELSE 0 END), 0) AS pumping_ml,
               FLOOR(COALESCE(SUM(CASE WHEN e.event_type = 'PUMPING' AND e.start_time >= days.d AND e.start_time < DATE_ADD(days.d, INTERVAL 1 DAY)
                                 THEN COALESCE(CAST(JSON_UNQUOTE(JSON_EXTRACT(e.event_data, '$.durationSeconds')) AS UNSIGNED), 0)
                                 ELSE 0 END), 0) / 60) AS pumping_minutes,
               COALESCE(SUM(CASE WHEN e.event_type = 'POOP' AND e.start_time >= days.d AND e.start_time < DATE_ADD(days.d, INTERVAL 1 DAY)
                                 THEN 1 ELSE 0 END), 0) AS poop_count,
               COALESCE(SUM(CASE WHEN e.event_type = 'PEE' AND e.start_time >= days.d AND e.start_time < DATE_ADD(days.d, INTERVAL 1 DAY)
                                 THEN 1 ELSE 0 END), 0) AS pee_count,
               COALESCE(SUM(CASE WHEN e.event_type = 'SLEEP' THEN
                    GREATEST(0, TIMESTAMPDIFF(MINUTE,
                      GREATEST(e.start_time, days.d),
                      LEAST(COALESCE(e.end_time, NOW()), DATE_ADD(days.d, INTERVAL 1 DAY))))
                    ELSE 0 END), 0) AS sleep_minutes
          FROM (
            SELECT DATE_SUB(CURRENT_DATE(), INTERVAL offsets.n DAY) AS d
              FROM (
                SELECT ones.n + tens.n * 10 AS n
                  FROM (
                    SELECT 0 AS n UNION ALL SELECT 1 UNION ALL SELECT 2 UNION ALL SELECT 3 UNION ALL SELECT 4
                    UNION ALL SELECT 5 UNION ALL SELECT 6 UNION ALL SELECT 7 UNION ALL SELECT 8 UNION ALL SELECT 9
                  ) ones
                  CROSS JOIN (
                    SELECT 0 AS n UNION ALL SELECT 1 UNION ALL SELECT 2 UNION ALL SELECT 3
                  ) tens
              ) offsets
             WHERE offsets.n <= #{daysMinusOne}
          ) days
          LEFT JOIN baby_event e
            ON e.baby_id = #{babyId}
           AND e.start_time < DATE_ADD(days.d, INTERVAL 1 DAY)
           AND (
                (e.event_type = 'SLEEP' AND COALESCE(e.end_time, NOW()) >= days.d)
                OR
                (e.event_type <> 'SLEEP' AND e.start_time >= days.d)
           )
         GROUP BY days.d
         ORDER BY days.d ASC
        """)
    List<DailyStatsRow> findDailyStats(@Param("babyId") long babyId, @Param("daysMinusOne") int daysMinusOne);
}
