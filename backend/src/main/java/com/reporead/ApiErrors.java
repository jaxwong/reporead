package com.reporead;

import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/** The one place API failures become HTTP responses. */
@RestControllerAdvice
class ApiErrors {
    private static final Logger LOG = LoggerFactory.getLogger(ApiErrors.class);

    record Body(String code, String message) {}

    @ExceptionHandler(ApiFailure.class)
    ResponseEntity<Body> failure(ApiFailure failure, HttpServletRequest request) {
        LOG.warn("API request failed; method={} path={} status={} code={}",
            request.getMethod(), request.getRequestURI(), failure.status.value(), failure.code);
        return ResponseEntity.status(failure.status).body(new Body(failure.code, failure.getMessage()));
    }
}
