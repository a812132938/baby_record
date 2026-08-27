package com.babyrecord;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

class AiMapperScopeContractTest {
    @Test
    void aiMapperCarriesFamilyAndBabyScopeAcrossPublicDataQueries() throws Exception {
        String source = Files.readString(Path.of("src/main/java/com/babyrecord/mapper/AiConversationMapper.java"));

        assertThat(source)
                .contains("family_id = #{familyId} AND baby_id = #{babyId}")
                .contains("b.id = #{babyId} AND b.family_id = #{familyId}")
                .contains("conversation_id = #{conversationId}")
                .contains("archived_at IS NULL")
                .contains("status = 'READY'")
                .contains("status IN ('ANALYZING', 'RESPONDING')")
                .contains("COUNT(e.id) AS source_event_count")
                .contains("SELECT NOW(3)")
                .contains("List<AiRecentEventRow> feedingEvents")
                .contains("ORDER BY e.start_time ASC, e.id ASC")
                .contains("e.event_type IN ('SLEEP','POOP')")
                .contains("ELSE '其他'")
                .doesNotContain("List<AiDailyRow>")
                .doesNotContain("SELECT *");
    }
}
