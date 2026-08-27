package com.babyrecord.auth;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class DeviceAuthInterceptorTest {

    @Test
    void corsPreflightDoesNotRequireADeviceSession() throws Exception {
        var authService = mock(DeviceAuthService.class);
        var request = mock(HttpServletRequest.class);
        var response = mock(HttpServletResponse.class);
        when(request.getMethod()).thenReturn("OPTIONS");

        var allowed = new DeviceAuthInterceptor(authService).preHandle(request, response, new Object());

        assertThat(allowed).isTrue();
        verifyNoInteractions(authService, response);
    }
}
