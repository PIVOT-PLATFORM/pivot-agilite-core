package fr.pivot.agilite.poker.ticket.dto;

/**
 * Computed consensus for a revealed planning poker ticket (US09.2.2), produced by {@code
 * fr.pivot.agilite.poker.ticket.ConsensusCalculator}.
 *
 * <p>ADR-026 §2 fixes the v1 scope to exactly these three figures — no dispersion/distribution
 * statistics (variance, standard deviation, histogram), explicitly deferred to v2+.
 *
 * @param mean     arithmetic mean of the ticket's <strong>numeric</strong> votes only (excludes
 *                 {@code "?"}), rounded to 1 decimal place, or {@code null} if there is no
 *                 numeric vote at all (zero votes cast, or every cast vote is {@code "?"})
 * @param median   median of the ticket's <strong>numeric</strong> votes only (same exclusion/
 *                 null rule as {@link #mean}), rounded to 1 decimal place
 * @param majority the single most frequent value among <strong>all</strong> cast votes,
 *                 {@code "?"} included — ties are broken deterministically by {@code
 *                 PokerCardDeck#FIBONACCI_VALUES} order; {@code null} only when zero votes were
 *                 cast on the ticket at all
 */
public record ConsensusResponse(Double mean, Double median, String majority) {
}
