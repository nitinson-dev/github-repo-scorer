package com.nitinson.reposcorer.scoring;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

class ScoringPropertiesTest {

    private static final ScoringProperties.Reference REFERENCE =
            new ScoringProperties.Reference(400_000, 100_000);

    @Test
    void rejectsWeightsThatDoNotSumToOne() {
        // The [0,100] score bound holds only when the weights sum to 1, so a
        // misconfiguration must fail fast at startup rather than silently
        // producing out-of-range scores.
        assertThatThrownBy(() -> new ScoringProperties(
                new ScoringProperties.Weights(0.5, 0.5, 0.5), REFERENCE, 180))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void acceptsWeightsThatSumToOne() {
        assertThatCode(() -> new ScoringProperties(
                new ScoringProperties.Weights(0.5, 0.3, 0.2), REFERENCE, 180))
                .doesNotThrowAnyException();
    }
}
