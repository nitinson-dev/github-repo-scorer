package com.nitinson.reposcorer.scoring;

import java.time.Instant;

public record ScoredRepository(
        String fullName,
        String url,
        String language,
        int stars,
        int forks,
        Instant lastPushed,
        double score) {
}
