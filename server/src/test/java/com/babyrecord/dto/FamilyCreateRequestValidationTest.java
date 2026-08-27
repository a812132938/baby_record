package com.babyrecord.dto;

import jakarta.validation.Validation;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;

import static org.assertj.core.api.Assertions.assertThat;

class FamilyCreateRequestValidationTest {

    @Test
    void acceptsACompleteRequestWithTodaysBirthDate() {
        try (var factory = Validation.buildDefaultValidatorFactory()) {
            var request = validRequest(LocalDate.now());

            assertThat(factory.getValidator().validate(request)).isEmpty();
        }
    }

    @Test
    void rejectsMissingOrFutureBirthDateAndMalformedDeviceId() {
        try (var factory = Validation.buildDefaultValidatorFactory()) {
            var validator = factory.getValidator();

            assertThat(validator.validate(validRequest(null)))
                    .extracting(violation -> violation.getPropertyPath().toString())
                    .contains("birthDate");
            assertThat(validator.validate(validRequest(LocalDate.now().plusDays(1))))
                    .extracting(violation -> violation.getPropertyPath().toString())
                    .contains("birthDate");

            var malformedDevice = new FamilyCreateRequest(
                    "小满之家", "小满", LocalDate.now(), "GIRL", 3200, "妈妈",
                    "11223344-5566-4788-8abc-112233445566",
                    "123456781234-1234-1234-123456789012", "手机"
            );
            assertThat(validator.validate(malformedDevice))
                    .extracting(violation -> violation.getPropertyPath().toString())
                    .contains("deviceId");
        }
    }

    @Test
    void rejectsBlankNamesAndNamesBeyondDatabaseLimits() {
        try (var factory = Validation.buildDefaultValidatorFactory()) {
            var request = new FamilyCreateRequest(
                    " ", "x".repeat(65), LocalDate.now(), "UNKNOWN", 99, " ",
                    "not-a-random-uuid",
                    "12345678-1234-1234-1234-123456789012", "x".repeat(121)
            );

            assertThat(factory.getValidator().validate(request))
                    .extracting(violation -> violation.getPropertyPath().toString())
                    .contains(
                            "familyName", "babyNickname", "gender", "birthWeightGrams",
                            "nickname", "creationKey", "deviceName"
                    );
        }
    }

    @Test
    void updatingBabyRequiresACompleteValidBirthProfile() {
        try (var factory = Validation.buildDefaultValidatorFactory()) {
            var validator = factory.getValidator();

            assertThat(validator.validate(new UpdateBabyRequest("小满", null, "GIRL", 3200)))
                    .extracting(violation -> violation.getPropertyPath().toString())
                    .contains("birthday");
            assertThat(validator.validate(new UpdateBabyRequest(
                    "小满", LocalDate.now().plusDays(1), "GIRL", 3200
            )))
                    .extracting(violation -> violation.getPropertyPath().toString())
                    .contains("birthday");
            assertThat(validator.validate(new UpdateBabyRequest(
                    "小满", LocalDate.now(), null, null
            )))
                    .extracting(violation -> violation.getPropertyPath().toString())
                    .contains("gender", "birthWeightGrams");
            assertThat(validator.validate(new UpdateBabyRequest(
                    "小满", LocalDate.now(), "UNKNOWN", 15001
            )))
                    .extracting(violation -> violation.getPropertyPath().toString())
                    .contains("gender", "birthWeightGrams");
        }
    }

    private FamilyCreateRequest validRequest(LocalDate birthDate) {
        return new FamilyCreateRequest(
                "小满之家", "小满", birthDate, "GIRL", 3200, "妈妈",
                "11223344-5566-4788-8abc-112233445566",
                "12345678-1234-1234-1234-123456789012", "手机"
        );
    }
}
