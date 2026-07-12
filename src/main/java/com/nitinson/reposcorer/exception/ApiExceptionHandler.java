package com.nitinson.reposcorer.exception;

import java.time.Instant;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.BindException;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import jakarta.servlet.http.HttpServletRequest;

@RestControllerAdvice
public class ApiExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(ApiExceptionHandler.class);

    @ExceptionHandler(BindException.class)
    public ResponseEntity<ErrorResponse> handleInvalidRequest(BindException ex, HttpServletRequest request) {
        return respond(HttpStatus.BAD_REQUEST, firstError(ex), request);
    }

    @ExceptionHandler(GitHubApiException.class)
    public ResponseEntity<ErrorResponse> handleGitHubApiError(GitHubApiException ex, HttpServletRequest request) {
        // A 422 means GitHub understood us but rejected the search query, so it's
        // worth passing through. Any other upstream status is opaque to the caller.
        HttpStatus status = ex.status().value() == HttpStatus.UNPROCESSABLE_ENTITY.value()
                ? HttpStatus.UNPROCESSABLE_ENTITY
                : HttpStatus.BAD_GATEWAY;
        // Upstream failure, not our bug: WARN so it's visible without alarming.
        log.warn("GitHub returned {} for {}: {}", ex.status().value(), request.getRequestURI(), ex.getMessage());
        return respond(status, ex.getMessage(), request);
    }

    @ExceptionHandler(GitHubUnavailableException.class)
    public ResponseEntity<ErrorResponse> handleGitHubUnavailable(GitHubUnavailableException ex,
                                                                 HttpServletRequest request) {
        log.warn("GitHub was unreachable for {}: {}", request.getRequestURI(), ex.getMessage());
        return respond(HttpStatus.GATEWAY_TIMEOUT, ex.getMessage(), request);
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ErrorResponse> handleUnexpected(Exception ex, HttpServletRequest request) {
        // We don't know what happened, so keep the stack trace: the client gets a
        // generic message and this is the only record left to diagnose from.
        log.error("Unexpected error handling {}", request.getRequestURI(), ex);
        return respond(HttpStatus.INTERNAL_SERVER_ERROR, "Unexpected error", request);
    }

    private static String firstError(BindException ex) {
        FieldError fieldError = ex.getBindingResult().getFieldError();
        if (fieldError == null) {
            return "Invalid request";
        }
        return fieldError.getField() + " " + fieldError.getDefaultMessage();
    }

    private static ResponseEntity<ErrorResponse> respond(HttpStatus status, String message,
                                                         HttpServletRequest request) {
        ErrorResponse body = new ErrorResponse(
                Instant.now(),
                status.value(),
                status.getReasonPhrase(),
                message,
                request.getRequestURI());
        return ResponseEntity.status(status).body(body);
    }
}
