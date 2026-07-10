package fr.pivot.agilite.wheel;

import fr.pivot.agilite.wheel.WeightedEntrySelector.Candidate;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Statistical and edge-case tests for {@link WeightedEntrySelector} (US14.2.1 backlog AC,
 * "Tests statistiques" section).
 *
 * <p>Runs entirely in-memory (no Spring context, no Testcontainers) so that the 1000-draw
 * statistical assertions are fast and cheap to run on every build — see the class-level javadoc
 * of {@link WeightedEntrySelector} for why the algorithm is deliberately decoupled from
 * persistence.
 *
 * <p>Tolerance rationale: for a binomial proportion {@code p} over {@code n = 1000} trials, the
 * standard deviation is {@code sqrt(p(1-p)/n)}, which is at most ~1.6 percentage points for every
 * {@code p} used below. A ± 5 percentage point tolerance is therefore at least ~3 standard
 * deviations from the true proportion in every assertion — a negligible flake probability — while
 * still failing hard for an obviously broken implementation (e.g. a uniform-random draw that
 * ignores weights entirely, which would land at 25 % per entry for the 4-entry scenario below).
 */
class WeightedEntrySelectorTest {

    private static final int TRIALS = 1000;
    private static final double TOLERANCE_PERCENTAGE_POINTS = 5.0;

    @Test
    void select_withDifferentWeightsAndNoAntiRepeat_matchesTheoreticalProportions() {
        Candidate weight1 = candidate(1);
        Candidate weight2 = candidate(2);
        Candidate weight3 = candidate(3);
        Candidate weight4 = candidate(4);
        List<Candidate> candidates = List.of(weight1, weight2, weight3, weight4);

        Map<UUID, Integer> counts = drawManyTimes(candidates, TRIALS);

        assertProportionWithinTolerance(counts, weight1.entryId(), TRIALS, 0.10);
        assertProportionWithinTolerance(counts, weight2.entryId(), TRIALS, 0.20);
        assertProportionWithinTolerance(counts, weight3.entryId(), TRIALS, 0.30);
        assertProportionWithinTolerance(counts, weight4.entryId(), TRIALS, 0.40);
    }

    @Test
    void select_withEqualWeights_distributesRoughlyEvenly() {
        Candidate a = candidate(1);
        Candidate b = candidate(1);
        List<Candidate> candidates = List.of(a, b);

        Map<UUID, Integer> counts = drawManyTimes(candidates, TRIALS);

        assertProportionWithinTolerance(counts, a.entryId(), TRIALS, 0.50);
        assertProportionWithinTolerance(counts, b.entryId(), TRIALS, 0.50);
    }

    @Test
    void applyAntiRepeat_reducedWeightMode_lowersButDoesNotZeroLastDrawnEntry() {
        Candidate lastDrawn = candidate(5);
        Candidate other = candidate(5);
        List<Candidate> nominal = List.of(lastDrawn, other);

        int occurrencesOfLastDrawn = 0;
        for (int i = 0; i < TRIALS; i++) {
            List<Candidate> effective =
                    WeightedEntrySelector.applyAntiRepeat(nominal, lastDrawn.entryId(), AntiRepeatMode.REDUCED_WEIGHT);
            Candidate chosen = WeightedEntrySelector.select(effective, ThreadLocalRandom.current());
            if (chosen.entryId().equals(lastDrawn.entryId())) {
                occurrencesOfLastDrawn++;
            }
        }

        // effective weights: lastDrawn -> max(1, 5/5) = 1, other -> 5 ; theoretical share = 1/6.
        double observed = (double) occurrencesOfLastDrawn / TRIALS;
        assertThat(observed * 100)
                .as("reduced_weight: last-drawn entry's observed share")
                .isCloseTo(100.0 / 6, org.assertj.core.data.Offset.offset(TOLERANCE_PERCENTAGE_POINTS));
        assertThat(observed).isLessThan(0.5);
    }

    @Test
    void applyAntiRepeat_excludeMode_neverSelectsLastDrawnEntry() {
        Candidate lastDrawn = candidate(5);
        Candidate other = candidate(5);
        List<Candidate> nominal = List.of(lastDrawn, other);

        for (int i = 0; i < TRIALS; i++) {
            List<Candidate> effective =
                    WeightedEntrySelector.applyAntiRepeat(nominal, lastDrawn.entryId(), AntiRepeatMode.EXCLUDE);
            Candidate chosen = WeightedEntrySelector.select(effective, ThreadLocalRandom.current());
            assertThat(chosen.entryId()).isNotEqualTo(lastDrawn.entryId());
        }
    }

    @Test
    void applyAntiRepeat_excludeModeWithSingleEntry_fallsBackToNominalWeightInsteadOfEmptyPool() {
        Candidate onlyEntry = candidate(7);
        List<Candidate> nominal = List.of(onlyEntry);

        List<Candidate> effective =
                WeightedEntrySelector.applyAntiRepeat(nominal, onlyEntry.entryId(), AntiRepeatMode.EXCLUDE);

        assertThat(effective).hasSize(1);
        assertThat(effective.get(0).weight()).isEqualTo(7);
        Candidate chosen = WeightedEntrySelector.select(effective, ThreadLocalRandom.current());
        assertThat(chosen.entryId()).isEqualTo(onlyEntry.entryId());
    }

    @Test
    void applyAntiRepeat_withNullLastDrawnEntryId_leavesWeightsUnchanged() {
        List<Candidate> nominal = List.of(candidate(3), candidate(7));

        List<Candidate> effective = WeightedEntrySelector.applyAntiRepeat(nominal, null, AntiRepeatMode.EXCLUDE);

        assertThat(effective).containsExactlyElementsOf(nominal);
    }

    @Test
    void applyAntiRepeat_withLastDrawnEntryNoLongerInWheel_leavesWeightsUnchanged() {
        List<Candidate> nominal = List.of(candidate(3), candidate(7));
        UUID removedEntryId = UUID.randomUUID();

        List<Candidate> effective =
                WeightedEntrySelector.applyAntiRepeat(nominal, removedEntryId, AntiRepeatMode.REDUCED_WEIGHT);

        assertThat(effective).containsExactlyElementsOf(nominal);
    }

    @Test
    void select_withEmptyCandidateList_throwsIllegalArgumentException() {
        assertThatThrownBy(() -> WeightedEntrySelector.select(List.of(), ThreadLocalRandom.current()))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void select_withAllZeroWeights_throwsIllegalArgumentException() {
        List<Candidate> allZero = List.of(
                new Candidate(UUID.randomUUID(), "A", 0),
                new Candidate(UUID.randomUUID(), "B", 0));

        assertThatThrownBy(() -> WeightedEntrySelector.select(allZero, ThreadLocalRandom.current()))
                .isInstanceOf(IllegalArgumentException.class);
    }

    private static Candidate candidate(final int weight) {
        return new Candidate(UUID.randomUUID(), "entry-" + weight + "-" + UUID.randomUUID(), weight);
    }

    private static Map<UUID, Integer> drawManyTimes(final List<Candidate> candidates, final int trials) {
        Map<UUID, Integer> counts = new HashMap<>();
        for (int i = 0; i < trials; i++) {
            Candidate chosen = WeightedEntrySelector.select(candidates, ThreadLocalRandom.current());
            counts.merge(chosen.entryId(), 1, Integer::sum);
        }
        return counts;
    }

    private static void assertProportionWithinTolerance(
            final Map<UUID, Integer> counts, final UUID entryId, final int trials, final double theoreticalShare) {
        double observedPercentage = 100.0 * counts.getOrDefault(entryId, 0) / trials;
        double theoreticalPercentage = 100.0 * theoreticalShare;
        assertThat(observedPercentage)
                .as("observed share of entry with theoretical share %.1f%%", theoreticalPercentage)
                .isCloseTo(theoreticalPercentage, org.assertj.core.data.Offset.offset(TOLERANCE_PERCENTAGE_POINTS));
    }
}
