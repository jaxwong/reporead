package com.reporead;

import org.springframework.http.HttpStatus;

/** An expected, typed failure published to API clients as {code, message}. Messages never contain credentials or note content. */
public final class ApiFailure extends RuntimeException {
    public final HttpStatus status;
    public final String code;

    public ApiFailure(HttpStatus status, String code, String message) {
        super(message);
        this.status = status;
        this.code = code;
    }
}
