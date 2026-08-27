package com.babyrecord.auth;

import org.junit.jupiter.api.Test;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

class FamilyCreationRecoveryCleanerTest {

    @Test
    void cleanupUsesAnIndependentTransactionAndDeletesExpiredRows() throws Exception {
        var mapper = mock(DeviceSessionMapper.class);
        var cleaner = new FamilyCreationRecoveryCleaner(mapper);

        cleaner.deleteExpired();

        verify(mapper).deleteExpiredFamilyCreationRecoveries();
        var transactional = FamilyCreationRecoveryCleaner.class
                .getMethod("deleteExpired")
                .getAnnotation(Transactional.class);
        assertThat(transactional.propagation()).isEqualTo(Propagation.REQUIRES_NEW);
    }
}
