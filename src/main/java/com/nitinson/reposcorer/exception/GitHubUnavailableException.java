package com.nitinson.reposcorer.exception;

/**
 * Raised when a GitHub search cannot be completed at all — timeout or connection failure.
 */
public class GitHubUnavailableException extends RuntimeException {

    public GitHubUnavailableException(String message, Throwable cause) {
        super(message, cause);
    }
}
