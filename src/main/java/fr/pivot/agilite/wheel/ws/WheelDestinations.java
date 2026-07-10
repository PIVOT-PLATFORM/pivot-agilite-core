package fr.pivot.agilite.wheel.ws;

import java.util.UUID;

/**
 * Single source of truth for STOMP destination naming of wheels (US14.3.1).
 *
 * <p><strong>Mirrors {@code fr.pivot.agilite.poker.ws.PokerRoomDestinations}/{@code
 * fr.pivot.agilite.retro.ws.RetroSessionDestinations}</strong> — same slash-separated, disjoint-
 * from-the-EN07.3-relay naming convention (see {@code PokerRoomDestinations}'s JavaDoc for the
 * full rationale: ActiveMQ's dot-hierarchy wildcard matching for {@code DLQ.agilite} versus this
 * module's own in-process {@code SimpleBroker} for ephemeral, per-resource pub/sub). Registered
 * as an additional, disjoint prefix on the same {@code SimpleBroker} instance as planning-poker
 * rooms and retro sessions (see {@code fr.pivot.agilite.config.WebSocketConfig}).
 *
 * <p>Unlike poker rooms and retro sessions, a wheel has no client-to-server application
 * destination — the draw itself is entirely computed and persisted server-side via {@code POST
 * /wheels/{wheelId}/spin} (REST, US14.2.1); this class therefore only ever defines a broadcast
 * topic, no {@code /app/agilite/wheels/...} prefix.
 *
 * @see fr.pivot.agilite.config.WebSocketConfig
 * @see WheelChannelInterceptor
 */
public final class WheelDestinations {

    /**
     * Prefix for STOMP topic destinations broadcasting a wheel's draw results to every
     * subscriber currently viewing that wheel. Full destination:
     * {@code /topic/agilite/wheels/{wheelId}}.
     */
    public static final String TOPIC_WHEEL_PREFIX = "/topic/agilite/wheels/";

    private WheelDestinations() {
    }

    /**
     * Builds the wheel broadcast topic destination — the one every viewer of a wheel subscribes
     * to.
     *
     * @param wheelId the wheel's identifier
     * @return {@code /topic/agilite/wheels/{wheelId}}
     */
    public static String wheelTopic(final UUID wheelId) {
        return TOPIC_WHEEL_PREFIX + wheelId;
    }

    /**
     * Extracts the wheel id segment immediately following {@link #TOPIC_WHEEL_PREFIX} in
     * {@code destination}, stopping at the next {@code /} if the destination has a sub-path
     * beyond the wheel id (none exists today, but this mirrors the poker/retro precedent for
     * forward compatibility).
     *
     * @param destination the full STOMP destination
     * @return the raw wheel id segment, or {@code null} if {@code destination} is exactly the
     *         prefix with nothing following it
     */
    public static String extractWheelId(final String destination) {
        String after = destination.substring(TOPIC_WHEEL_PREFIX.length());
        if (after.isEmpty()) {
            return null;
        }
        int slash = after.indexOf('/');
        return slash < 0 ? after : after.substring(0, slash);
    }
}
