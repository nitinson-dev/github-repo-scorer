package com.nitinson.reposcorer.exception;

import java.time.Duration;

/**
 * Raised when the circuit breaker guarding GitHub is open, so the call is
 * short-circuited without reaching GitHub at all. Carries the circuit's
 * open-state wait so the caller can be told when it's worth trying again.
 */
public class GitHubCircuitOpenException extends RuntimeException {

    private final Duration retryAfter;

    public GitHubCircuitOpenException(Duration retryAfter, String message, Throwable cause) {
        super(message, cause);
        this.retryAfter = retryAfter;
    }

    public Duration retryAfter() {
        return retryAfter;
    }
}
