package com.nitinson.reposcorer.scoring;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "scoring")
public record ScoringProperties(
        Weights weights,
        Reference reference,
        int recencyHalfLifeDays) {

    /**
     * Relative importance of each factor. Expected to sum to 1.0.
     */
    public record Weights(double stars, double forks, double recency) {
    }

    /**
     * Star/fork counts that map to 1.0 on the log curve — roughly today's
     * largest counts on GitHub.
     */
    public record Reference(long stars, long forks) {
    }
}
