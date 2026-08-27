package com.babyrecord.auth;

import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.HttpMethod;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;

import java.io.IOException;
import java.util.Arrays;

@Component
public class DeviceAuthInterceptor implements HandlerInterceptor {
    public static final String REQUEST_ATTRIBUTE = "deviceSession";
    private final DeviceAuthService authService;

    public DeviceAuthInterceptor(DeviceAuthService authService) {
        this.authService = authService;
    }

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) throws IOException {
        if (HttpMethod.OPTIONS.matches(request.getMethod())) return true;
        var rawToken = findCookie(request, DeviceAuthService.COOKIE_NAME);
        var session = authService.resolve(rawToken);
        if (session == null) {
            response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
            response.setContentType("application/json;charset=UTF-8");
            response.getWriter().write("{\"message\":\"device_not_authorized\"}");
            return false;
        }
        request.setAttribute(REQUEST_ATTRIBUTE, session.principal());
        return true;
    }

    public static String findCookie(HttpServletRequest request, String name) {
        Cookie[] cookies = request.getCookies();
        if (cookies == null) return null;
        return Arrays.stream(cookies)
                .filter(c -> name.equals(c.getName()))
                .map(Cookie::getValue)
                .findFirst()
                .orElse(null);
    }
}
