package com.nitinson.reposcorer.exception;

import java.time.Duration;

/**
 * Raised when GitHub rejects a search because a rate limit is exhausted — a 429,
 * or a 403 with no quota remaining. Carries the server-advised wait before a
 * retry when GitHub supplies one ({@code null} if it does not).
 */
public class GitHubRateLimitException extends RuntimeException {

    private final Duration retryAfter;

    public GitHubRateLimitException(Duration retryAfter, String message, Throwable cause) {
        super(message, cause);
        this.retryAfter = retryAfter;
    }

    public Duration retryAfter() {
        return retryAfter;
    }
}
