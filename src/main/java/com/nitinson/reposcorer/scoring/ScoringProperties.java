package com.nitinson.reposcorer.scoring;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "scoring")
public record ScoringProperties(
        Weights weights,
        Reference reference,
        int recencyHalfLifeDays) {

    public ScoringProperties {
        // The score's [0,100] bound holds only if the weights sum to 1, so
        // reject a misconfiguration at startup rather than emit silent
        // out-of-range scores. A small tolerance absorbs float representation.
        double sum = weights.stars() + weights.forks() + weights.recency();
        if (Math.abs(sum - 1.0) > 1e-6) {
            throw new IllegalArgumentException(
                    "scoring.weights must sum to 1.0 but sum to " + sum);
        }
    }

    /**
     * Relative importance of each factor. Must sum to 1.0.
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
