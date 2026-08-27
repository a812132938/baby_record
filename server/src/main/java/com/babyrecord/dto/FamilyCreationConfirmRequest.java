package com.babyrecord.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;

public record FamilyCreationConfirmRequest(
        @NotBlank
        @Pattern(regexp = "(?i)^[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$")
        String creationKey
) {}
