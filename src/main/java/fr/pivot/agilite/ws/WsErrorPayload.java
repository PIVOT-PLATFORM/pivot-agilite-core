package fr.pivot.agilite.ws;

/**
 * Payload sent to a session's {@code /user/queue/errors} when one of its STOMP frames
 * (SUBSCRIBE or SEND) is rejected by a room's channel interceptor (e.g.
 * {@link fr.pivot.agilite.poker.ws.PokerChannelInterceptor}).
 *
 * <p>The {@code error} field carries a human-readable reason (not localised — intended for
 * developer debugging, not end-user display).
 */
public record WsErrorPayload(String error) {
}
