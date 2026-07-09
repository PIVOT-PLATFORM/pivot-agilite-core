package fr.pivot.agilite.config;

import fr.pivot.agilite.poker.ws.PokerRoomDestinations;
import org.junit.jupiter.api.Test;

import java.util.Arrays;

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

    /**
     * Security AC (EN09.1): the new planning-poker room broker's prefixes must never overlap
     * with the EN07.3 ActiveMQ relay's domain prefix in either direction — this is what allows
     * Spring to register a {@code SimpleBroker} (room traffic) and a {@code StompBrokerRelay}
     * (cross-domain bus) simultaneously without ambiguity about which one handles a given
     * destination (see {@link WebSocketConfig#configureMessageBroker}).
     */
    @Test
    void roomBrokerPrefixesNeverOverlapTheActiveMqRelayPrefix() {
        assertThat(Arrays.asList(WebSocketConfig.ROOM_BROKER_PREFIXES))
                .contains(PokerRoomDestinations.TOPIC_ROOM_PREFIX.substring(
                        0, PokerRoomDestinations.TOPIC_ROOM_PREFIX.length() - 1));

        String roomDestination = "/topic/agilite/poker/11111111-1111-1111-1111-111111111111";
        assertThat(roomDestination).doesNotStartWith(WebSocketConfig.DOMAIN_TOPIC_PREFIX);

        String relayDestination = WebSocketConfig.DOMAIN_TOPIC_PREFIX + "capacity-updated";
        for (String roomBrokerPrefix : WebSocketConfig.ROOM_BROKER_PREFIXES) {
            assertThat(relayDestination).doesNotStartWith(roomBrokerPrefix);
        }
    }
}
