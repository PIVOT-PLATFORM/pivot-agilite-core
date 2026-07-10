package fr.pivot.agilite.retro.card.dto;

import java.util.UUID;

/**
 * A single card's shape once revealed — content in clear, deliberately never authorship (US20.1.2a).
 *
 * <p>Used both by the {@code CARDS_REVEALED} STOMP broadcast and the {@code POST
 * /retro/sessions/{id}/reveal} REST response, grouped by column key in both places — see {@code
 * RetroPhaseService#reveal}.
 *
 * @param id      the card's identifier — needed by US20.1.2b (dot-voting) to attach votes
 * @param content the card's full content
 */
public record RevealedCard(UUID id, String content) {
}
