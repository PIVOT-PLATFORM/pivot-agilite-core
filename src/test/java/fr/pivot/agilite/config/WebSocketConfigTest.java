package fr.pivot.agilite.config;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests for {@link WebSocketConfig} — verifies the domain isolation contract without
 * requiring a running broker (see {@link WebSocketConfigIT} for the connectivity proof).
 */
class WebSocketConfigTest {

    /**
     * Security AC: this module's STOMP relay must only ever be scoped to its own domain
     * prefix, with a trailing separator so a similarly-named future domain (e.g.
     * "agilitex") can never accidentally prefix-match and have its traffic relayed by this
     * module.
     */
    @Test
    void domainPrefixIsScopedToAgiliteOnly() {
        assertThat(WebSocketConfig.DOMAIN_TOPIC_PREFIX)
                .isEqualTo("/topic/agilite.")
                .startsWith("/topic/agilite")
                .endsWith(".");
    }

    /**
     * Given a destination belonging to another domain, when compared against this module's
     * relay prefix, then it must never match — the isolation boundary enforced by Spring's
     * {@code AbstractBrokerMessageHandler.checkDestinationPrefix}.
     */
    @Test
    void otherDomainDestinationsNeverMatchThisModulePrefix() {
        assertThat("/topic/pilotage.roadmap-updated").doesNotStartWith(WebSocketConfig.DOMAIN_TOPIC_PREFIX);
        assertThat("/topic/collaboratif.board-updated").doesNotStartWith(WebSocketConfig.DOMAIN_TOPIC_PREFIX);
        assertThat("/topic/agilitex.other").doesNotStartWith(WebSocketConfig.DOMAIN_TOPIC_PREFIX);
    }

    /**
     * Given a destination belonging to this module's own domain, then it must match its
     * relay prefix — the positive counterpart of the isolation check above.
     */
    @Test
    void ownDomainDestinationMatchesThisModulePrefix() {
        assertThat("/topic/agilite.capacity-updated").startsWith(WebSocketConfig.DOMAIN_TOPIC_PREFIX);
    }
}
