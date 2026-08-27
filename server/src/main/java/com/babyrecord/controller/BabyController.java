package com.babyrecord.controller;

import com.babyrecord.auth.DeviceAuthInterceptor;
import com.babyrecord.auth.DeviceSessionPrincipal;
import com.babyrecord.dto.BabySummary;
import com.babyrecord.dto.UpdateBabyRequest;
import com.babyrecord.service.BabyEventService;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/babies/{babyId}")
public class BabyController {
    private final BabyEventService service;

    public BabyController(BabyEventService service) {
        this.service = service;
    }

    @PatchMapping
    public BabySummary update(@PathVariable long babyId,
                              @RequestAttribute(DeviceAuthInterceptor.REQUEST_ATTRIBUTE) DeviceSessionPrincipal principal,
                              @Valid @RequestBody UpdateBabyRequest request) {
        return service.updateBaby(babyId, principal.familyId(), request);
    }
}
