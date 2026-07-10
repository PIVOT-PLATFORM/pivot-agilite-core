package fr.pivot.agilite.retro.card.dto;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Defensive-copy helper for the {@code Map<String, List<RevealedCard>>} shape shared by {@code
 * CardsRevealedEvent} and {@code RevealResponse} (US20.1.2a).
 *
 * <p>Both records use this in their compact canonical constructor rather than storing the caller-
 * supplied map/lists directly (SpotBugs {@code EI_EXPOSE_REP2}) — a genuine, fixable exposure
 * (unlike this codebase's established, intentional exclusion for constructor-injected Spring
 * singleton beans, see {@code spotbugs-exclude.xml}). Preserves insertion order (backed by {@link
 * LinkedHashMap}) — both callers document columns as ordered by first-submission time.
 */
public final class RevealedColumns {

    private RevealedColumns() {
    }

    /**
     * Returns an unmodifiable, order-preserving deep copy of {@code source} — empty if
     * {@code source} is {@code null}.
     *
     * @param source the map to copy, or {@code null}
     * @return an unmodifiable copy, never {@code null}
     */
    public static Map<String, List<RevealedCard>> immutableCopy(final Map<String, List<RevealedCard>> source) {
        if (source == null) {
            return Map.of();
        }
        Map<String, List<RevealedCard>> copy = new LinkedHashMap<>();
        source.forEach((columnKey, cards) -> copy.put(columnKey, List.copyOf(cards)));
        return Collections.unmodifiableMap(copy);
    }
}
