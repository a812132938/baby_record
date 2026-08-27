package com.babyrecord.service;

final class AiProviderException extends RuntimeException {
    private final String errorCode;

    AiProviderException(String errorCode) {
        super(errorCode);
        this.errorCode = errorCode;
    }

    String errorCode() {
        return errorCode;
    }
}
