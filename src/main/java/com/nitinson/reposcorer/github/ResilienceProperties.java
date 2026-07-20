package com.nitinson.reposcorer.github;

import java.time.Duration;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Resilience settings for calls to the GitHub API. Retries cover transient
 * failures only — 5xx responses and timeouts; rate limits and 4xx are never
 * retried (see {@code GitHubClient}).
 */
@ConfigurationProperties(prefix = "github.resilience")
public record ResilienceProperties(
        // Total attempts per page fetch, including the first (1 disables retrying).
        int maxAttempts,
        // Wait before the first retry; grows by backoffMultiplier each attempt.
        Duration initialBackoff,
        double backoffMultiplier,
        // Percentage of failures in the window that trips the circuit open.
        float failureRateThreshold,
        // Number of recent calls the failure rate is computed over.
        int slidingWindowSize,
        // Minimum calls before the failure rate is evaluated at all.
        int minimumNumberOfCalls,
        // How long the circuit stays open before probing GitHub again; also the
        // Retry-After we advise callers while it's open.
        Duration waitInOpenState) {
}
