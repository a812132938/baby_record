package com.babyrecord.controller;

import com.babyrecord.auth.DeviceAuthInterceptor;
import com.babyrecord.auth.DeviceSessionMapper;
import com.babyrecord.auth.DeviceSessionPrincipal;
import com.babyrecord.dto.FamilyDeviceResponse;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/v1/family")
public class FamilyController {
    private final DeviceSessionMapper mapper;

    public FamilyController(DeviceSessionMapper mapper) {
        this.mapper = mapper;
    }

    @GetMapping("/invite")
    public Map<String, String> invite(
            @RequestAttribute(DeviceAuthInterceptor.REQUEST_ATTRIBUTE) DeviceSessionPrincipal principal) {
        return Map.of("inviteCode", mapper.findInviteCodeByFamilyId(principal.familyId()));
    }

    @GetMapping("/devices")
    public List<FamilyDeviceResponse> devices(
            @RequestAttribute(DeviceAuthInterceptor.REQUEST_ATTRIBUTE) DeviceSessionPrincipal principal) {
        return mapper.findDevicesByFamilyId(principal.familyId());
    }

    @DeleteMapping("/devices/{deviceId}")
    public ResponseEntity<Void> revokeDevice(
            @PathVariable long deviceId,
            @RequestAttribute(DeviceAuthInterceptor.REQUEST_ATTRIBUTE) DeviceSessionPrincipal principal) {
        if (!"ADMIN".equalsIgnoreCase(principal.role())) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "只有管理员可以移除家庭设备");
        }
        if (deviceId == principal.deviceId()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "请使用退出登录移除当前设备");
        }
        var familyId = mapper.findDeviceFamilyId(deviceId);
        if (familyId == null || familyId != principal.familyId()) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "设备不存在");
        }
        mapper.revokeDevice(principal.familyId(), deviceId);
        return ResponseEntity.noContent().build();
    }
}
