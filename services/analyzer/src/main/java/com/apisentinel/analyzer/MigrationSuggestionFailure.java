package com.apisentinel.analyzer;

import org.springframework.http.HttpStatus;

final class MigrationSuggestionFailure extends RuntimeException {
    final HttpStatus status;
    final String code;
    final Integer retryAfterSeconds;

    MigrationSuggestionFailure(HttpStatus status, String code, String detail) {
        this(status, code, detail, null);
    }

    MigrationSuggestionFailure(HttpStatus status, String code, String detail, Integer retryAfterSeconds) {
        super(detail);
        this.status = status;
        this.code = code;
        this.retryAfterSeconds = retryAfterSeconds;
    }

    static MigrationSuggestionFailure invalidOutput() {
        return new MigrationSuggestionFailure(HttpStatus.BAD_GATEWAY, "GEMINI_INVALID_RESPONSE",
                "Gemini did not return usable recommendations. Try again later.");
    }
}
