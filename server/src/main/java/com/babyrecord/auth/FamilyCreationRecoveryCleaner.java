package com.babyrecord.auth;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Service
public class FamilyCreationRecoveryCleaner {
    private final DeviceSessionMapper mapper;

    public FamilyCreationRecoveryCleaner(DeviceSessionMapper mapper) {
        this.mapper = mapper;
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void deleteExpired() {
        mapper.deleteExpiredFamilyCreationRecoveries();
    }
}
