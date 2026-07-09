package fr.pivot.agilite.poker.ws;

import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests for {@link PokerRoomDestinations} — the EN09.1 destination naming contract that
 * US09.1.2/US09.2.1/US09.2.2 depend on.
 */
class PokerRoomDestinationsTest {

    private static final UUID ROOM_ID = UUID.fromString("11111111-1111-1111-1111-111111111111");

    /**
     * Given a room id, when building its broadcast topic, then the result is
     * {@code /topic/agilite/poker/{roomId}}.
     */
    @Test
    void roomTopicBuildsExpectedDestination() {
        assertThat(PokerRoomDestinations.roomTopic(ROOM_ID))
                .isEqualTo("/topic/agilite/poker/" + ROOM_ID);
    }

    /**
     * Given a bare topic destination (no sub-path after the room id), when extracting the room
     * id, then the full remainder is returned.
     */
    @Test
    void extractRoomIdFromBareTopicDestination() {
        String destination = PokerRoomDestinations.TOPIC_ROOM_PREFIX + ROOM_ID;
        assertThat(PokerRoomDestinations.extractRoomId(destination, PokerRoomDestinations.TOPIC_ROOM_PREFIX))
                .isEqualTo(ROOM_ID.toString());
    }

    /**
     * Given an application destination with a sub-path after the room id (e.g. an action name),
     * when extracting the room id, then only the segment up to the next {@code /} is returned.
     */
    @Test
    void extractRoomIdStopsAtNextSlashForAppDestination() {
        String destination = PokerRoomDestinations.APP_ROOM_PREFIX + ROOM_ID + "/vote";
        assertThat(PokerRoomDestinations.extractRoomId(destination, PokerRoomDestinations.APP_ROOM_PREFIX))
                .isEqualTo(ROOM_ID.toString());
    }

    /**
     * Given a destination that is exactly the prefix with nothing following it, when extracting
     * the room id, then {@code null} is returned (no room id present at all).
     */
    @Test
    void extractRoomIdReturnsNullForBarePrefix() {
        assertThat(PokerRoomDestinations.extractRoomId(
                PokerRoomDestinations.TOPIC_ROOM_PREFIX, PokerRoomDestinations.TOPIC_ROOM_PREFIX))
                .isNull();
    }
}
