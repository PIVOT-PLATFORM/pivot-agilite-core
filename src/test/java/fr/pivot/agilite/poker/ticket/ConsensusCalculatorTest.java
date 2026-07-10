package fr.pivot.agilite.poker.ticket;

import fr.pivot.agilite.poker.ticket.dto.ConsensusResponse;
import org.junit.jupiter.api.Test;

import java.util.Collections;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests for {@link ConsensusCalculator} (US09.2.2) — pure computation, no Spring context.
 */
class ConsensusCalculatorTest {

    /**
     * Given an odd count of numeric votes with a non-terminating average, when consensus is
     * computed, then {@code mean} is rounded to 1 decimal and {@code median} is the exact middle
     * value.
     */
    @Test
    void compute_oddCountRequiringRounding_roundsMeanToOneDecimal() {
        ConsensusResponse consensus = ConsensusCalculator.compute(List.of("1", "1", "2"));

        assertThat(consensus.mean()).isEqualTo(1.3);
        assertThat(consensus.median()).isEqualTo(1.0);
        assertThat(consensus.majority()).isEqualTo("1");
    }

    /**
     * Given an even count of numeric votes, when {@code median} is computed, then it is the
     * average of the two middle values.
     */
    @Test
    void compute_evenCount_medianIsAverageOfTwoMiddleValues() {
        ConsensusResponse consensus = ConsensusCalculator.compute(List.of("3", "5"));

        assertThat(consensus.mean()).isEqualTo(4.0);
        assertThat(consensus.median()).isEqualTo(4.0);
    }

    /**
     * Given a larger odd-count set with duplicates, when consensus is computed, then {@code mean}
     * and {@code median} are both correct standard statistics over the sorted numeric set.
     */
    @Test
    void compute_oddCountWithDuplicates_computesMeanAndMedian() {
        ConsensusResponse consensus = ConsensusCalculator.compute(List.of("3", "5", "5", "8", "8"));

        assertThat(consensus.mean()).isEqualTo(5.8);
        assertThat(consensus.median()).isEqualTo(5.0);
        assertThat(consensus.majority()).isEqualTo("5");
    }

    /**
     * Given votes mixing numeric values and {@code "?"}, when {@code mean}/{@code median} are
     * computed, then {@code "?"} is fully excluded from both — but {@code majority} still
     * considers it and may legitimately diverge from mean/median's implied value.
     */
    @Test
    void compute_mixedWithQuestionMark_excludesFromMeanMedianButCountsForMajority() {
        ConsensusResponse consensus = ConsensusCalculator.compute(List.of("3", "?", "?", "5"));

        assertThat(consensus.mean()).isEqualTo(4.0);
        assertThat(consensus.median()).isEqualTo(4.0);
        assertThat(consensus.majority()).isEqualTo("?");
    }

    /**
     * Given a frequency tie between two values for {@code majority}, when the tie is broken, then
     * the value appearing earliest in {@code PokerCardDeck#FIBONACCI_VALUES} order wins.
     */
    @Test
    void compute_majorityTie_breaksByDeckOrder() {
        ConsensusResponse consensus = ConsensusCalculator.compute(List.of("5", "5", "8", "8", "3"));

        assertThat(consensus.majority()).isEqualTo("5");
    }

    /**
     * Given zero votes at all, when consensus is computed, then every field is {@code null}.
     */
    @Test
    void compute_noVotes_returnsAllNull() {
        ConsensusResponse consensus = ConsensusCalculator.compute(Collections.emptyList());

        assertThat(consensus.mean()).isNull();
        assertThat(consensus.median()).isNull();
        assertThat(consensus.majority()).isNull();
    }

    /**
     * Given votes that are all {@code "?"} (no numeric vote at all), when consensus is computed,
     * then {@code mean}/{@code median} are {@code null} but {@code majority} is still resolvable.
     */
    @Test
    void compute_allQuestionMarks_meanMedianNullButMajorityResolved() {
        ConsensusResponse consensus = ConsensusCalculator.compute(List.of("?", "?"));

        assertThat(consensus.mean()).isNull();
        assertThat(consensus.median()).isNull();
        assertThat(consensus.majority()).isEqualTo("?");
    }

    /**
     * Given a single numeric vote, when consensus is computed, then {@code mean} and {@code
     * median} both equal that single value and {@code majority} is that same value.
     */
    @Test
    void compute_singleVote_meanMedianMajorityAllEqualToIt() {
        ConsensusResponse consensus = ConsensusCalculator.compute(List.of("13"));

        assertThat(consensus.mean()).isEqualTo(13.0);
        assertThat(consensus.median()).isEqualTo(13.0);
        assertThat(consensus.majority()).isEqualTo("13");
    }

    /**
     * Given no frequency tie at all (every value distinct), when {@code majority} is computed,
     * then it is simply the single value with the highest count.
     */
    @Test
    void compute_noTie_majorityIsHighestFrequencyValue() {
        ConsensusResponse consensus = ConsensusCalculator.compute(List.of("1", "2", "2", "2", "3"));

        assertThat(consensus.majority()).isEqualTo("2");
    }
}
