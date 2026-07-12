package com.nitinson.reposcorer.scoring;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;

import org.junit.jupiter.api.Test;

import com.nitinson.reposcorer.github.GitHubRepository;

class ScoringServiceTest {

    private static final Instant NOW = Instant.parse("2026-07-12T00:00:00Z");

    private final ScoringService service = new ScoringService(new ScoringProperties(
            new ScoringProperties.Weights(0.5, 0.3, 0.2),
            new ScoringProperties.Reference(400_000, 100_000),
            180));

    @Test
    void scoreStaysWithinBounds() {
        GitHubRepository huge = repo("a/huge", 1_000_000, 500_000, NOW);
        GitHubRepository empty = repo("a/empty", 0, 0, NOW.minus(3650, ChronoUnit.DAYS));

        assertThat(service.score(huge, NOW)).isBetween(0.0, 100.0);
        assertThat(service.score(empty, NOW)).isBetween(0.0, 100.0);
    }

    @Test
    void moreStarsScoreHigher() {
        GitHubRepository fewer = repo("a/fewer", 100, 50, NOW);
        GitHubRepository more = repo("a/more", 5_000, 50, NOW);

        assertThat(service.score(more, NOW)).isGreaterThan(service.score(fewer, NOW));
    }

    @Test
    void recentPushScoresHigherThanStalePush() {
        GitHubRepository fresh = repo("a/fresh", 500, 100, NOW);
        GitHubRepository stale = repo("a/stale", 500, 100, NOW.minus(720, ChronoUnit.DAYS));

        assertThat(service.score(fresh, NOW)).isGreaterThan(service.score(stale, NOW));
    }

    @Test
    void pushAtHalfLifeHalvesTheRecencyFactor() {
        // Only recency differs, so the score gap is exactly wRecency * (1 - 0.5) * 100 = 10.
        GitHubRepository now = repo("a/now", 500, 100, NOW);
        GitHubRepository halfLifeAgo = repo("a/half", 500, 100, NOW.minus(180, ChronoUnit.DAYS));

        assertThat(service.score(now, NOW) - service.score(halfLifeAgo, NOW)).isCloseTo(10.0, within(0.1));
    }

    @Test
    void scoreIsRoundedToOneDecimalOnOutput() {
        GitHubRepository repository = repo("a/repo", 1234, 567, NOW.minus(37, ChronoUnit.DAYS));

        double score = service.scoreAndRank(List.of(repository), NOW).getFirst().score();

        assertThat(score * 10).isCloseTo(Math.rint(score * 10), within(1e-9));
    }

    @Test
    void ranksByDescendingScore() {
        GitHubRepository low = repo("a/low", 10, 1, NOW.minus(400, ChronoUnit.DAYS));
        GitHubRepository high = repo("a/high", 50_000, 8_000, NOW);
        GitHubRepository mid = repo("a/mid", 2_000, 300, NOW.minus(60, ChronoUnit.DAYS));

        List<ScoredRepository> ranked = service.scoreAndRank(List.of(low, high, mid), NOW);

        assertThat(ranked).extracting(ScoredRepository::fullName)
                .containsExactly("a/high", "a/mid", "a/low");
    }

    @Test
    void keepsDuplicateStatRepositoriesInStableOrder() {
        GitHubRepository first = repo("a/first", 1_000, 200, NOW);
        GitHubRepository second = repo("a/second", 1_000, 200, NOW);

        List<ScoredRepository> ranked = service.scoreAndRank(List.of(first, second), NOW);

        assertThat(ranked).extracting(ScoredRepository::fullName)
                .containsExactly("a/first", "a/second");
    }

    private static GitHubRepository repo(String fullName, int stars, int forks, Instant lastPushed) {
        return new GitHubRepository(fullName, "https://github.com/" + fullName, "Java", stars, forks, lastPushed);
    }
}
