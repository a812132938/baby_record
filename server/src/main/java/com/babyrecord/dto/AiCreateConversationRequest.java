package com.babyrecord.dto;

import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;

public record AiCreateConversationRequest(
        @NotBlank
        @Pattern(regexp = "^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[1-5][0-9a-fA-F]{3}-[89abAB][0-9a-fA-F]{3}-[0-9a-fA-F]{12}$")
        String clientRequestId,
        @AssertTrue(message = "必须同意将宝宝记录发送给 AI 服务处理")
        boolean dataProcessingAccepted
) {}
