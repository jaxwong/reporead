package com.reporead;

import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

/** The one place API failures become HTTP responses. */
@RestControllerAdvice
class ApiErrors {
    private static final Logger LOG = LoggerFactory.getLogger(ApiErrors.class);

    record Body(ApiFailure.Code code, String message) {}

    @ExceptionHandler(ApiFailure.class)
    ResponseEntity<Body> failure(ApiFailure failure, HttpServletRequest request) {
        LOG.warn("API request failed; method={} path={} status={} code={}",
            request.getMethod(), request.getRequestURI(), failure.code.status, failure.code);
        return ResponseEntity.status(failure.code.status).body(new Body(failure.code, failure.getMessage()));
    }

    /** A body, parameter, or path value the route cannot read is the client's error, typed like every other failure. */
    @ExceptionHandler({HttpMessageNotReadableException.class, MissingServletRequestParameterException.class, MethodArgumentTypeMismatchException.class})
    ResponseEntity<Body> unreadable(Exception error, HttpServletRequest request) {
        LOG.warn("API request unreadable; method={} path={} error={}", request.getMethod(), request.getRequestURI(), error.getClass().getSimpleName());
        String message = error instanceof MissingServletRequestParameterException missing
            ? "Missing request parameter " + missing.getParameterName() + "."
            : error instanceof MethodArgumentTypeMismatchException mismatch
                ? "Request value " + mismatch.getName() + " has the wrong type."
                : "The request body is not valid JSON for this route.";
        return ResponseEntity.status(ApiFailure.Code.INVALID_REQUEST.status).body(new Body(ApiFailure.Code.INVALID_REQUEST, message));
    }
}
