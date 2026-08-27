package com.babyrecord.controller;

import com.babyrecord.auth.DeviceAuthInterceptor;
import com.babyrecord.auth.DeviceAuthService;
import com.babyrecord.auth.DeviceSessionPrincipal;
import com.babyrecord.dto.DeviceClaimRequest;
import com.babyrecord.dto.FamilyCreateRequest;
import com.babyrecord.dto.FamilyCreationConfirmRequest;
import com.babyrecord.dto.MeResponse;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseCookie;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.time.Duration;

@RestController
@RequestMapping("/api/v1/auth")
public class AuthController {
    private final DeviceAuthService authService;
    private final boolean secureCookie;

    public AuthController(DeviceAuthService authService,
                          @Value("${app.auth.secure-cookie}") boolean secureCookie) {
        this.authService = authService;
        this.secureCookie = secureCookie;
    }

    @PostMapping("/device/claim")
    public ResponseEntity<MeResponse> claim(@Valid @RequestBody DeviceClaimRequest request) {
        return sessionResponse(authService.claim(request));
    }

    @PostMapping("/family/create")
    public ResponseEntity<MeResponse> createFamily(@Valid @RequestBody FamilyCreateRequest request) {
        return sessionResponse(authService.createFamily(request));
    }

    @PostMapping("/family/create/confirm")
    public ResponseEntity<Void> confirmFamilyCreation(
            @Valid @RequestBody FamilyCreationConfirmRequest request,
            HttpServletRequest servletRequest
    ) {
        var principal = (DeviceSessionPrincipal) servletRequest.getAttribute(DeviceAuthInterceptor.REQUEST_ATTRIBUTE);
        if (principal == null) return ResponseEntity.status(401).build();
        authService.confirmFamilyCreation(request.creationKey(), principal);
        return ResponseEntity.noContent().build();
    }

    private ResponseEntity<MeResponse> sessionResponse(DeviceAuthService.ClaimedSession session) {
        var cookie = ResponseCookie.from(DeviceAuthService.COOKIE_NAME, session.rawToken())
                .httpOnly(true)
                .secure(secureCookie)
                .sameSite("Lax")
                .path("/")
                .maxAge(session.maxAge())
                .build();
        return ResponseEntity.ok()
                .header(HttpHeaders.SET_COOKIE, cookie.toString())
                .body(session.me());
    }

    @GetMapping("/me")
    public ResponseEntity<MeResponse> me(HttpServletRequest request) {
        var rawToken = DeviceAuthInterceptor.findCookie(request, DeviceAuthService.COOKIE_NAME);
        var session = authService.resolve(rawToken);
        if (session == null) return ResponseEntity.status(401).build();
        return ResponseEntity.ok(authService.toMe(session.principal()));
    }

    @PostMapping("/logout")
    public ResponseEntity<Void> logout(HttpServletRequest request) {
        var rawToken = DeviceAuthInterceptor.findCookie(request, DeviceAuthService.COOKIE_NAME);
        authService.revoke(rawToken);
        var cookie = ResponseCookie.from(DeviceAuthService.COOKIE_NAME, "")
                .httpOnly(true)
                .secure(secureCookie)
                .sameSite("Lax")
                .path("/")
                .maxAge(Duration.ZERO)
                .build();
        return ResponseEntity.noContent().header(HttpHeaders.SET_COOKIE, cookie.toString()).build();
    }
}
