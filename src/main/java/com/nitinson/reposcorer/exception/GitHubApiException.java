package com.nitinson.reposcorer.exception;

import org.springframework.http.HttpStatusCode;

/**
 * Raised when GitHub answers a search with a non-2xx status.
 */
public class GitHubApiException extends RuntimeException {

    private final HttpStatusCode status;

    public GitHubApiException(HttpStatusCode status, String message, Throwable cause) {
        super(message, cause);
        this.status = status;
    }

    public HttpStatusCode status() {
        return status;
    }
}
