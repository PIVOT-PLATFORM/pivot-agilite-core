package fr.pivot.agilite.poker.ws;

import java.util.UUID;

/**
 * Single source of truth for STOMP destination naming of planning-poker rooms (EN09.1).
 *
 * <p><strong>Slash, not dot — deliberately different from the EN07.3 domain prefix.</strong>
 * {@code fr.pivot.agilite.config.WebSocketConfig} scopes the EN07.3 {@code StompBrokerRelay} to
 * the dot-hierarchy prefix {@code /topic/agilite.} because ActiveMQ's wildcard destination
 * matching (used by the broker-side {@code DLQ.agilite} dead-letter policy) only matches
 * dot-separated segments — that relay carries the future cross-module-domain event bus, nothing
 * room-specific. Room traffic (this class) is registered on its own, disjoint, in-process
 * {@code SimpleBroker} prefix instead (see {@code WebSocketConfig#configureMessageBroker}):
 * ephemeral, single-instance, low-latency room pub/sub has no need for the shared durable
 * broker, exactly mirroring {@code pivot-collaboratif-core}'s split between
 * {@code /topic/whiteboard/*} (SimpleBroker, per-board rooms) and {@code /topic/collaboratif.*}
 * (StompBrokerRelay, cross-domain bus). Because {@link #TOPIC_ROOM_PREFIX} never contains a dot
 * immediately after {@code agilite}, it can never collide with the EN07.3 relay prefix's
 * {@code startsWith} matching in either direction — verified in {@code PokerRoomDestinationsTest}.
 *
 * @see fr.pivot.agilite.config.WebSocketConfig
 */
public final class PokerRoomDestinations {

    /**
     * Prefix for STOMP topic destinations broadcasting room state (votes, reveal, participant
     * updates…) to every subscriber of a given room. Full destination:
     * {@code /topic/agilite/poker/{roomId}}.
     */
    public static final String TOPIC_ROOM_PREFIX = "/topic/agilite/poker/";

    /**
     * Prefix for STOMP application destinations carrying client-to-server room actions (vote,
     * reveal request…). Full destination: {@code /app/agilite/poker/{roomId}/{action}}. Matched
     * against the {@code /app/agilite} application prefix already registered by EN07.3 — no
     * further broker configuration is required for this prefix.
     */
    public static final String APP_ROOM_PREFIX = "/app/agilite/poker/";

    private PokerRoomDestinations() {
    }

    /**
     * Builds the room broadcast topic destination for a given room.
     *
     * @param roomId the room's identifier
     * @return {@code /topic/agilite/poker/{roomId}}
     */
    public static String roomTopic(final UUID roomId) {
        return TOPIC_ROOM_PREFIX + roomId;
    }

    /**
     * Extracts the room id segment immediately following {@code prefix} in {@code destination},
     * stopping at the next {@code /} if the destination has a sub-path beyond the room id (e.g.
     * an application destination such as {@code /app/agilite/poker/{roomId}/vote}).
     *
     * @param destination the full STOMP destination
     * @param prefix      the prefix to strip — one of {@link #TOPIC_ROOM_PREFIX} or
     *                    {@link #APP_ROOM_PREFIX}
     * @return the raw room id segment, or {@code null} if {@code destination} is exactly the
     *         prefix with nothing following it
     */
    public static String extractRoomId(final String destination, final String prefix) {
        String after = destination.substring(prefix.length());
        if (after.isEmpty()) {
            return null;
        }
        int slash = after.indexOf('/');
        return slash < 0 ? after : after.substring(0, slash);
    }
}
