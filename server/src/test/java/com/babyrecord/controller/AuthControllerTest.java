package com.babyrecord.controller;

import com.babyrecord.auth.DeviceAuthInterceptor;
import com.babyrecord.auth.DeviceAuthService;
import com.babyrecord.auth.DeviceSessionPrincipal;
import com.babyrecord.dto.MeResponse;
import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.time.Duration;
import java.time.LocalDateTime;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class AuthControllerTest {

    private static final String CREATION_KEY = "11223344-5566-4788-8abc-112233445566";

    @Test
    void createFamilyIsPublicAndReturnsClaimCompatibleIdentityAndHttpOnlyCookie() throws Exception {
        var authService = mock(DeviceAuthService.class);
        var me = new MeResponse(24L, 23L, 21L, 22L, "妈妈", "ADMIN");
        when(authService.createFamily(any())).thenReturn(new DeviceAuthService.ClaimedSession(
                "raw-token", LocalDateTime.now().plusDays(30), Duration.ofDays(30), me
        ));
        MockMvc mockMvc = MockMvcBuilders
                .standaloneSetup(new AuthController(authService, false))
                .build();

        mockMvc.perform(post("/api/v1/auth/family/create")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                            {
                              "familyName": "小满之家",
                              "babyNickname": "小满",
                              "birthDate": "2026-08-01",
                              "gender": "GIRL",
                              "birthWeightGrams": 3200,
                              "nickname": "妈妈",
                              "creationKey": "11223344-5566-4788-8abc-112233445566",
                              "deviceId": "12345678-1234-1234-1234-123456789012",
                              "deviceName": "手机"
                            }
                            """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.deviceId").value(24))
                .andExpect(jsonPath("$.userId").value(23))
                .andExpect(jsonPath("$.familyId").value(21))
                .andExpect(jsonPath("$.babyId").value(22))
                .andExpect(jsonPath("$.role").value("ADMIN"))
                .andExpect(header().string("Set-Cookie", org.hamcrest.Matchers.allOf(
                        org.hamcrest.Matchers.containsString("br_device=raw-token"),
                        org.hamcrest.Matchers.containsString("HttpOnly"),
                        org.hamcrest.Matchers.containsString("SameSite=Lax")
                )));

        verify(authService).createFamily(any());
    }

    @Test
    void authenticatedCreatorCanConfirmFamilyCreation() throws Exception {
        var authService = mock(DeviceAuthService.class);
        var principal = new DeviceSessionPrincipal(24L, 23L, 21L, "妈妈", "ADMIN");
        when(authService.resolve("raw-token")).thenReturn(
                new DeviceAuthService.ResolvedSession("raw-token", principal)
        );
        MockMvc mockMvc = MockMvcBuilders
                .standaloneSetup(new AuthController(authService, false))
                .addInterceptors(new DeviceAuthInterceptor(authService))
                .build();

        mockMvc.perform(post("/api/v1/auth/family/create/confirm")
                        .cookie(new Cookie(DeviceAuthService.COOKIE_NAME, "raw-token"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"creationKey\":\"" + CREATION_KEY + "\"}"))
                .andExpect(status().isNoContent());

        verify(authService).confirmFamilyCreation(CREATION_KEY, principal);
    }

    @Test
    void unauthenticatedClientCannotConfirmFamilyCreation() throws Exception {
        var authService = mock(DeviceAuthService.class);
        MockMvc mockMvc = MockMvcBuilders
                .standaloneSetup(new AuthController(authService, false))
                .addInterceptors(new DeviceAuthInterceptor(authService))
                .build();

        mockMvc.perform(post("/api/v1/auth/family/create/confirm")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"creationKey\":\"" + CREATION_KEY + "\"}"))
                .andExpect(status().isUnauthorized());

        verify(authService, never()).confirmFamilyCreation(any(), any());
    }

    @Test
    void authenticatedConfirmationRejectsMalformedCreationKey() throws Exception {
        var authService = mock(DeviceAuthService.class);
        var principal = new DeviceSessionPrincipal(24L, 23L, 21L, "妈妈", "ADMIN");
        when(authService.resolve("raw-token")).thenReturn(
                new DeviceAuthService.ResolvedSession("raw-token", principal)
        );
        MockMvc mockMvc = MockMvcBuilders
                .standaloneSetup(new AuthController(authService, false))
                .addInterceptors(new DeviceAuthInterceptor(authService))
                .build();

        mockMvc.perform(post("/api/v1/auth/family/create/confirm")
                        .cookie(new Cookie(DeviceAuthService.COOKIE_NAME, "raw-token"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"creationKey\":\"not-a-uuid\"}"))
                .andExpect(status().isBadRequest());

        verify(authService, never()).confirmFamilyCreation(any(), any());
    }
}
