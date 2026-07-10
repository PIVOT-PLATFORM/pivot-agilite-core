package fr.pivot.agilite.retro.ws;

import java.util.UUID;

/**
 * Single source of truth for STOMP destination naming of retrospective sessions (US20.1.2a).
 *
 * <p><strong>Mirrors {@code fr.pivot.agilite.poker.ws.PokerRoomDestinations}</strong> — same
 * slash-separated, disjoint-from-the-EN07.3-relay naming convention (see that class's JavaDoc for
 * the full rationale: ActiveMQ's dot-hierarchy wildcard matching for {@code DLQ.agilite} versus
 * this module's own in-process {@code SimpleBroker} for ephemeral, per-session room traffic).
 * Registered as an additional, disjoint prefix on the same {@code SimpleBroker} instance as
 * planning-poker rooms (see {@code fr.pivot.agilite.config.WebSocketConfig}).
 *
 * <p><strong>Facilitator-only preview topic.</strong> Unlike planning poker, a retrospective
 * session has a second, narrower broadcast destination — {@link #facilitatorTopic(UUID)} — that
 * only the session's facilitator may subscribe to (enforced by {@link RetroChannelInterceptor}).
 * This is what lets the facilitator see full card content as soon as it is submitted, while every
 * other participant only ever receives the masked count on {@link #roomTopic(UUID)} until the
 * facilitator explicitly triggers {@code CARDS_REVEALED} (US20.1.2a AC — "aucun participant autre
 * que l'animateur" sees content in clear before reveal).
 *
 * @see fr.pivot.agilite.config.WebSocketConfig
 * @see RetroChannelInterceptor
 */
public final class RetroSessionDestinations {

    /**
     * Prefix for STOMP topic destinations broadcasting session state (masked card-added counts,
     * phase changes, revealed cards…) to every subscriber of a given session. Full destination:
     * {@code /topic/agilite/retro/{sessionId}}.
     */
    public static final String TOPIC_ROOM_PREFIX = "/topic/agilite/retro/";

    /**
     * Suffix appended to a session's topic to build its facilitator-only preview destination.
     * Full destination: {@code /topic/agilite/retro/{sessionId}/facilitator}.
     */
    public static final String FACILITATOR_TOPIC_SUFFIX = "/facilitator";

    /**
     * Prefix for STOMP application destinations carrying client-to-server session actions (card
     * submission…). Full destination: {@code /app/agilite/retro/{sessionId}/{action}}. Matched
     * against the {@code /app/agilite} application prefix already registered by EN07.3 — no
     * further broker configuration is required for this prefix.
     */
    public static final String APP_ROOM_PREFIX = "/app/agilite/retro/";

    private RetroSessionDestinations() {
    }

    /**
     * Builds the session broadcast topic destination — the one every participant subscribes to.
     *
     * @param sessionId the session's identifier
     * @return {@code /topic/agilite/retro/{sessionId}}
     */
    public static String roomTopic(final UUID sessionId) {
        return TOPIC_ROOM_PREFIX + sessionId;
    }

    /**
     * Builds the session's facilitator-only preview topic destination.
     *
     * @param sessionId the session's identifier
     * @return {@code /topic/agilite/retro/{sessionId}/facilitator}
     */
    public static String facilitatorTopic(final UUID sessionId) {
        return TOPIC_ROOM_PREFIX + sessionId + FACILITATOR_TOPIC_SUFFIX;
    }

    /**
     * Extracts the session id segment immediately following {@code prefix} in
     * {@code destination}, stopping at the next {@code /} if the destination has a sub-path
     * beyond the session id (e.g. the facilitator topic, or an application destination such as
     * {@code /app/agilite/retro/{sessionId}/cards}).
     *
     * @param destination the full STOMP destination
     * @param prefix      the prefix to strip — one of {@link #TOPIC_ROOM_PREFIX} or
     *                    {@link #APP_ROOM_PREFIX}
     * @return the raw session id segment, or {@code null} if {@code destination} is exactly the
     *         prefix with nothing following it
     */
    public static String extractSessionId(final String destination, final String prefix) {
        String after = destination.substring(prefix.length());
        if (after.isEmpty()) {
            return null;
        }
        int slash = after.indexOf('/');
        return slash < 0 ? after : after.substring(0, slash);
    }

    /**
     * Returns whether {@code destination} is exactly a session's facilitator-only topic (as
     * opposed to its regular, all-participants topic).
     *
     * @param destination the full STOMP destination
     * @param sessionId   the session's identifier
     * @return {@code true} if {@code destination} equals {@link #facilitatorTopic(UUID)} for
     *         this session
     */
    public static boolean isFacilitatorTopic(final String destination, final UUID sessionId) {
        return facilitatorTopic(sessionId).equals(destination);
    }
}
