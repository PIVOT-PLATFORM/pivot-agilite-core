package fr.pivot.agilite.poker.ticket;

import fr.pivot.agilite.poker.PokerCardDeck;
import fr.pivot.agilite.poker.ticket.dto.ConsensusResponse;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Pure computation of a revealed ticket's consensus (US09.2.2) from the raw list of cast vote
 * values — no persistence, no I/O, independently unit-testable.
 *
 * <p>ADR-026 §2 fixes the v1 scope: mean/median over the <strong>numeric</strong> subset of
 * {@code PokerCardDeck#FIBONACCI_VALUES} only ({@code "?"} excluded), and a majority value over
 * <strong>all</strong> cast values ({@code "?"} included). No dispersion/distribution statistics
 * — deferred to v2+.
 */
public final class ConsensusCalculator {

    /** Decimal places retained for {@code mean}/{@code median} — cosmetic rounding, not precision. */
    private static final int ROUNDING_FACTOR = 10;

    private ConsensusCalculator() {
    }

    /**
     * Computes the consensus for a ticket's full set of cast vote values.
     *
     * @param values every cast vote's raw value (including {@code "?"}), in any order
     * @return the computed {@link ConsensusResponse} — every field {@code null} if {@code values}
     *         is empty; {@code mean}/{@code median} additionally {@code null} if none of the
     *         values is numeric (e.g. every cast vote is {@code "?"})
     */
    public static ConsensusResponse compute(final List<String> values) {
        if (values.isEmpty()) {
            return new ConsensusResponse(null, null, null);
        }

        List<Double> numericValues = new ArrayList<>();
        for (String value : values) {
            Double parsed = parseNumeric(value);
            if (parsed != null) {
                numericValues.add(parsed);
            }
        }

        Double mean = numericValues.isEmpty() ? null : round(mean(numericValues));
        Double median = numericValues.isEmpty() ? null : round(median(numericValues));
        String majority = computeMajority(values);

        return new ConsensusResponse(mean, median, majority);
    }

    /**
     * Parses a card value as a number, for the mean/median numeric subset.
     *
     * @param value the raw card value
     * @return the parsed value, or {@code null} if it is not numeric (e.g. {@code "?"})
     */
    private static Double parseNumeric(final String value) {
        try {
            return Double.parseDouble(value);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /**
     * Arithmetic mean of a non-empty numeric list.
     *
     * @param numericValues the numeric subset, non-empty
     * @return the mean, unrounded
     */
    private static double mean(final List<Double> numericValues) {
        double sum = 0;
        for (double value : numericValues) {
            sum += value;
        }
        return sum / numericValues.size();
    }

    /**
     * Standard median of a non-empty numeric list: the middle value for an odd count, the average
     * of the two middle values for an even count.
     *
     * @param numericValues the numeric subset, non-empty
     * @return the median, unrounded
     */
    private static double median(final List<Double> numericValues) {
        List<Double> sorted = new ArrayList<>(numericValues);
        Collections.sort(sorted);
        int size = sorted.size();
        int middle = size / 2;
        if (size % 2 == 1) {
            return sorted.get(middle);
        }
        return (sorted.get(middle - 1) + sorted.get(middle)) / 2.0;
    }

    /**
     * Rounds a value to {@link #ROUNDING_FACTOR} decimal precision (1 decimal place).
     *
     * @param value the raw value
     * @return the rounded value
     */
    private static double round(final double value) {
        return Math.round(value * ROUNDING_FACTOR) / (double) ROUNDING_FACTOR;
    }

    /**
     * Computes the most frequent value among all cast votes ({@code "?"} included), breaking any
     * frequency tie deterministically by {@code PokerCardDeck#FIBONACCI_VALUES} order.
     *
     * @param values every cast vote's raw value, non-empty
     * @return the majority value
     */
    private static String computeMajority(final List<String> values) {
        Map<String, Long> counts = values.stream()
                .collect(Collectors.groupingBy(value -> value, Collectors.counting()));
        long maxCount = counts.values().stream().mapToLong(Long::longValue).max().orElseThrow();

        for (String deckValue : PokerCardDeck.FIBONACCI_VALUES) {
            if (counts.getOrDefault(deckValue, 0L) == maxCount) {
                return deckValue;
            }
        }
        // Defensive fallback — every persisted vote value is validated against
        // PokerCardDeck#FIBONACCI_VALUES at submission time (US09.2.1), so a value absent from
        // the deck order should never actually reach this point.
        return counts.entrySet().stream()
                .filter(entry -> entry.getValue() == maxCount)
                .map(Map.Entry::getKey)
                .findFirst()
                .orElseThrow();
    }
}
