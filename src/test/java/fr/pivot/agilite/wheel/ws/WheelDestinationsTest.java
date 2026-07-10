package fr.pivot.agilite.wheel.ws;

import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests for {@link WheelDestinations} — the US14.3.1 broadcast destination naming contract.
 */
class WheelDestinationsTest {

    private static final UUID WHEEL_ID = UUID.fromString("11111111-1111-1111-1111-111111111111");

    /**
     * Given a wheel id, when building its broadcast topic, then the result is
     * {@code /topic/agilite/wheels/{wheelId}}.
     */
    @Test
    void wheelTopicBuildsExpectedDestination() {
        assertThat(WheelDestinations.wheelTopic(WHEEL_ID)).isEqualTo("/topic/agilite/wheels/" + WHEEL_ID);
    }

    /**
     * Given a bare topic destination (no sub-path after the wheel id), when extracting the wheel
     * id, then the full remainder is returned.
     */
    @Test
    void extractWheelIdFromBareTopicDestination() {
        String destination = WheelDestinations.TOPIC_WHEEL_PREFIX + WHEEL_ID;
        assertThat(WheelDestinations.extractWheelId(destination)).isEqualTo(WHEEL_ID.toString());
    }

    /**
     * Given a destination with a sub-path after the wheel id (forward-compatibility with a
     * possible future sub-destination), when extracting the wheel id, then only the segment up
     * to the next {@code /} is returned.
     */
    @Test
    void extractWheelIdStopsAtNextSlash() {
        String destination = WheelDestinations.TOPIC_WHEEL_PREFIX + WHEEL_ID + "/future-sub-path";
        assertThat(WheelDestinations.extractWheelId(destination)).isEqualTo(WHEEL_ID.toString());
    }

    /**
     * Given a destination that is exactly the prefix with nothing following it, when extracting
     * the wheel id, then {@code null} is returned (no wheel id present at all).
     */
    @Test
    void extractWheelIdReturnsNullForBarePrefix() {
        assertThat(WheelDestinations.extractWheelId(WheelDestinations.TOPIC_WHEEL_PREFIX)).isNull();
    }

    /**
     * Security AC: the wheel broker prefix must never overlap the EN07.3 ActiveMQ relay's domain
     * prefix, mirroring the poker/retro precedent's own equivalent guarantee.
     */
    @Test
    void wheelTopicNeverMatchesTheActiveMqRelayDomainPrefix() {
        assertThat(WheelDestinations.wheelTopic(WHEEL_ID)).doesNotStartWith("/topic/agilite.");
    }
}
