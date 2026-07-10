package fr.pivot.agilite.retro.phase.dto;

import fr.pivot.agilite.retro.card.dto.RevealedCard;
import fr.pivot.agilite.retro.card.dto.RevealedColumns;

import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Response returned by {@code POST /retro/sessions/{id}/reveal} to the facilitator who triggered
 * it — the same shape broadcast to every participant as {@link CardsRevealedEvent} (US20.1.2a).
 *
 * @param sessionId the session that was revealed
 * @param cardCount the total number of cards revealed, across all columns
 * @param columns   every card, grouped by column key
 */
public record RevealResponse(UUID sessionId, int cardCount, Map<String, List<RevealedCard>> columns) {

    /**
     * Canonical constructor — stores an unmodifiable, order-preserving copy of {@code columns}
     * rather than the caller-supplied map/lists directly (see {@code RevealedColumns}).
     */
    public RevealResponse {
        columns = RevealedColumns.immutableCopy(columns);
    }
}
