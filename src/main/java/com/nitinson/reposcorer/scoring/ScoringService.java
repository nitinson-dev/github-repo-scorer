package com.nitinson.reposcorer.scoring;

import java.time.Duration;
import java.time.Instant;
import java.util.Comparator;
import java.util.List;

import org.springframework.stereotype.Service;

import com.nitinson.reposcorer.github.GitHubRepository;

@Service
public class ScoringService {

    private final ScoringProperties properties;

    public ScoringService(ScoringProperties properties) {
        this.properties = properties;
    }

    /**
     * Scores every repository against the same {@code now} and returns them
     * ranked by descending score. Ties on the score fall back to stars, then
     * forks, then most recent push so the ordering is stable.
     */
    public List<ScoredRepository> scoreAndRank(List<GitHubRepository> repositories, Instant now) {
        Comparator<Ranked> ranking = Comparator
                .comparingDouble(Ranked::score)
                .thenComparingInt(ranked -> ranked.repository().stars())
                .thenComparingInt(ranked -> ranked.repository().forks())
                .thenComparingLong(ranked -> lastPushedMillis(ranked.repository()))
                .reversed();

        return repositories.stream()
                .map(repository -> new Ranked(repository, score(repository, now)))
                .sorted(ranking)
                .map(this::toScoredRepository)
                .toList();
    }

    /**
     * Popularity score in [0, 100] for a single repository.
     */
    public double score(GitHubRepository repository, Instant now) {
        double starsNormalized = logNormalize(repository.stars(), properties.reference().stars());
        double forksNormalized = logNormalize(repository.forks(), properties.reference().forks());
        double recencyNormalized = recencyDecay(repository.lastPushed(), now);

        ScoringProperties.Weights weights = properties.weights();
        double weighted = weights.stars() * starsNormalized
                + weights.forks() * forksNormalized
                + weights.recency() * recencyNormalized;
        return 100.0 * weighted;
    }

    /**
     * Maps a raw count onto [0, 1] via a log curve. A count equal to the
     * reference lands at exactly 1.0 (the reference itself is a rough "today's
     * largest" estimate); the log gives diminishing returns without a hard
     * plateau, and larger repos are clamped so the factor never exceeds 1.
     */
    private double logNormalize(long value, long reference) {
        if (value <= 0) {
            return 0.0;
        }
        double normalized = Math.log1p(value) / Math.log1p(reference);
        return Math.min(1.0, normalized);
    }

    private double recencyDecay(Instant lastPushed, Instant now) {
        if (lastPushed == null) {
            return 0.0;
        }
        long ageDays = Math.max(0, Duration.between(lastPushed, now).toDays());
        double halfLives = (double) ageDays / properties.recencyHalfLifeDays();
        return Math.pow(0.5, halfLives);
    }

    private ScoredRepository toScoredRepository(Ranked ranked) {
        GitHubRepository repository = ranked.repository();
        double displayScore = Math.round(ranked.score() * 10.0) / 10.0;
        return new ScoredRepository(
                repository.fullName(),
                repository.url(),
                repository.language(),
                repository.stars(),
                repository.forks(),
                repository.lastPushed(),
                displayScore);
    }

    private static long lastPushedMillis(GitHubRepository repository) {
        return repository.lastPushed() == null ? 0L : repository.lastPushed().toEpochMilli();
    }

    private record Ranked(GitHubRepository repository, double score) {
    }
}
