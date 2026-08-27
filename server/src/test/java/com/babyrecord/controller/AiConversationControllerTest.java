package com.babyrecord.controller;

import com.babyrecord.auth.DeviceAuthInterceptor;
import com.babyrecord.auth.DeviceSessionPrincipal;
import com.babyrecord.dto.AiConversationDetail;
import com.babyrecord.service.AiConversationService;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.time.LocalDateTime;
import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.request;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class AiConversationControllerTest {
    private final DeviceSessionPrincipal principal = new DeviceSessionPrincipal(1, 2, 3, "妈妈", "ADMIN");

    @Test
    void createRequiresExplicitDataProcessingAcceptanceAndReturnsAccepted() throws Exception {
        var service = mock(AiConversationService.class);
        when(service.create(eq(9L), eq(principal), any())).thenReturn(detail("ANALYZING"));
        var mvc = MockMvcBuilders.standaloneSetup(new AiConversationController(service)).build();

        mvc.perform(post("/api/v1/babies/9/ai/conversations")
                        .requestAttr(DeviceAuthInterceptor.REQUEST_ATTRIBUTE, principal)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                            {"clientRequestId":"11223344-5566-4788-8abc-112233445566","dataProcessingAccepted":true}
                            """))
                .andExpect(status().isAccepted());

        mvc.perform(post("/api/v1/babies/9/ai/conversations")
                        .requestAttr(DeviceAuthInterceptor.REQUEST_ATTRIBUTE, principal)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                            {"clientRequestId":"11223344-5566-4788-8abc-112233445567","dataProcessingAccepted":false}
                            """))
                .andExpect(status().isBadRequest());

        verify(service).create(eq(9L), eq(principal), any());
    }

    @Test
    void questionIsAcceptedOnlyForBoundedNonBlankContent() throws Exception {
        var service = mock(AiConversationService.class);
        when(service.question(eq(9L), eq(10L), eq(principal), any())).thenReturn(detail("RESPONDING"));
        var mvc = MockMvcBuilders.standaloneSetup(new AiConversationController(service)).build();

        mvc.perform(post("/api/v1/babies/9/ai/conversations/10/messages")
                        .requestAttr(DeviceAuthInterceptor.REQUEST_ATTRIBUTE, principal)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                            {"clientMessageId":"11223344-5566-4788-8abc-112233445566","content":"今天睡眠趋势怎么样？"}
                            """))
                .andExpect(status().isAccepted());

        mvc.perform(post("/api/v1/babies/9/ai/conversations/10/messages")
                        .requestAttr(DeviceAuthInterceptor.REQUEST_ATTRIBUTE, principal)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                            {"clientMessageId":"11223344-5566-4788-8abc-112233445567","content":""}
                            """))
                .andExpect(status().isBadRequest());

        verify(service).question(eq(9L), eq(10L), eq(principal), any());
    }

    @Test
    void messageStreamUsesNonBufferingHeadersAndPassesTheAuthenticatedScope() throws Exception {
        var service = mock(AiConversationService.class);
        var emitter = new SseEmitter(0L);
        when(service.stream(9L, 10L, 11L, principal)).thenReturn(emitter);
        var mvc = MockMvcBuilders.standaloneSetup(new AiConversationController(service)).build();

        mvc.perform(get("/api/v1/babies/9/ai/conversations/10/messages/11/stream")
                        .requestAttr(DeviceAuthInterceptor.REQUEST_ATTRIBUTE, principal))
                .andExpect(status().isOk())
                .andExpect(request().asyncStarted())
                .andExpect(header().string("Content-Type", "text/event-stream"))
                .andExpect(header().string("Cache-Control", "no-cache, no-store, no-transform"))
                .andExpect(header().string("X-Accel-Buffering", "no"));

        verify(service).stream(9L, 10L, 11L, principal);
        emitter.complete();
    }

    private AiConversationDetail detail(String status) {
        return new AiConversationDetail(10, "宝宝记录分析", status, "deepseek-v4-flash", null,
                LocalDateTime.now(), LocalDateTime.now(), null, List.of());
    }
}
