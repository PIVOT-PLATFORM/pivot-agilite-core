package fr.pivot.agilite.wheel;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.random.RandomGenerator;

/**
 * Pure, persistence-free weighted random selection over a set of wheel entries, including the
 * anti-repeat weight adjustment (US14.2.1).
 *
 * <p>Deliberately decoupled from {@link Wheel}/JPA and from any Spring wiring so it can be
 * exercised thousands of times per test in plain JUnit (the statistical distribution AC of
 * US14.2.1 requires 1000 independent draws per scenario, which would be impractical against
 * Testcontainers). {@link WheelDrawService} maps {@link Wheel}/{@link WheelEntry} into {@link
 * Candidate} records, calls {@link #applyAntiRepeat}, then {@link #select}.
 */
public final class WeightedEntrySelector {

    /** Divisor applied to a weight to compute its {@code reduced_weight} anti-repeat value. */
    static final int REDUCED_WEIGHT_DIVISOR = 5;

    private WeightedEntrySelector() {
    }

    /**
     * A wheel entry with its weight for a single draw.
     *
     * @param entryId the entry's unique identifier
     * @param label   the entry's display label
     * @param weight  the weight to use for this draw, {@code >= 0}
     */
    public record Candidate(UUID entryId, String label, int weight) {
    }

    /**
     * Applies the anti-repeat adjustment to the nominal weights, producing the effective weights
     * for a single draw.
     *
     * <p>If {@code lastDrawnEntryId} is {@code null}, or does not match any candidate (e.g. the
     * entry was since removed from the wheel via {@code PUT /wheels/{id}}), every candidate keeps
     * its nominal weight unchanged — there is nothing to adjust for a first draw.
     *
     * <p>{@code REDUCED_WEIGHT}: the matching candidate's weight becomes {@code max(1, weight /
     * REDUCED_WEIGHT_DIVISOR)} (integer division) — always strictly positive, so this mode never
     * needs a fallback.
     *
     * <p>{@code EXCLUDE}: the matching candidate's weight becomes {@code 0}. If that leaves every
     * candidate at weight {@code 0} (only possible when the wheel has exactly one entry, which is
     * also the last-drawn one), the exclusion is ignored for this draw and nominal weights are
     * used instead — a draw must always be possible on a non-empty wheel.
     *
     * @param nominal          the wheel's entries at their nominal (US14.1.1) weight
     * @param lastDrawnEntryId {@code wheel.lastDrawnEntryId}, or {@code null}
     * @param mode             the anti-repeat mode requested for this draw
     * @return the effective candidates for this draw, same size/order as {@code nominal}
     */
    public static List<Candidate> applyAntiRepeat(
            final List<Candidate> nominal, final UUID lastDrawnEntryId, final AntiRepeatMode mode) {
        if (lastDrawnEntryId == null) {
            return nominal;
        }
        List<Candidate> adjusted = new ArrayList<>(nominal.size());
        for (Candidate candidate : nominal) {
            if (candidate.entryId().equals(lastDrawnEntryId)) {
                adjusted.add(new Candidate(candidate.entryId(), candidate.label(), effectiveWeight(candidate, mode)));
            } else {
                adjusted.add(candidate);
            }
        }
        if (sumWeights(adjusted) <= 0) {
            // EXCLUDE-mode fallback: zeroing out the only entry left nothing to draw from.
            return nominal;
        }
        return adjusted;
    }

    /**
     * Selects one candidate with probability proportional to its weight among the total.
     *
     * @param candidates the non-empty list of candidates, sum of weights strictly positive —
     *                   guaranteed by {@link #applyAntiRepeat} together with the caller rejecting
     *                   a wheel with zero entries ({@code WheelEmptyException})
     * @param random     the random source — injectable so tests can use a seeded/counted generator
     *                   for determinism and statistical assertions, and production code can use
     *                   {@link java.util.concurrent.ThreadLocalRandom}
     * @return the selected candidate
     * @throws IllegalArgumentException if {@code candidates} is empty or every weight is
     *     {@code <= 0}
     */
    public static Candidate select(final List<Candidate> candidates, final RandomGenerator random) {
        if (candidates.isEmpty()) {
            throw new IllegalArgumentException("candidates must not be empty");
        }
        long total = sumWeights(candidates);
        if (total <= 0) {
            throw new IllegalArgumentException("sum of weights must be strictly positive");
        }

        long pick = random.nextLong(total);
        long cumulative = 0;
        for (Candidate candidate : candidates) {
            cumulative += candidate.weight();
            if (pick < cumulative) {
                return candidate;
            }
        }
        // Unreachable given the invariants above (cumulative sums to exactly `total`, and pick is
        // strictly < total) — last element as a defensive fallback rather than throwing from a
        // state that arithmetic proves impossible.
        return candidates.get(candidates.size() - 1);
    }

    private static int effectiveWeight(final Candidate candidate, final AntiRepeatMode mode) {
        if (mode == AntiRepeatMode.EXCLUDE) {
            return 0;
        }
        return Math.max(1, candidate.weight() / REDUCED_WEIGHT_DIVISOR);
    }

    private static long sumWeights(final List<Candidate> candidates) {
        long total = 0;
        for (Candidate candidate : candidates) {
            total += candidate.weight();
        }
        return total;
    }
}
