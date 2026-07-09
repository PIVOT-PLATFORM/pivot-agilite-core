package fr.pivot.agilite.ws;

import java.security.Principal;

/**
 * Anonymous per-connection identity carried through a STOMP WebSocket session on the shared
 * {@code /ws/agilite} endpoint.
 *
 * <p><strong>This is not an authentication mechanism.</strong> It carries no claim about which
 * user or tenant is behind the connection — {@link #connectionId()} is a random value minted
 * server-side by {@link WsConnectionHandshakeHandler} at handshake time, never derived from any
 * client-supplied input (header, query param, cookie). Its only purpose is to give Spring's
 * {@code SimpMessagingTemplate#convertAndSendToUser} a stable per-session name so a denied STOMP
 * frame can be answered with an error notification on that session's own
 * {@code /user/queue/errors}, without needing every session to be authenticated first.
 *
 * <p>This module ({@code pivot-agilite-core}) has no real bearer-token validation yet ({@code
 * fr.pivot:pivot-core-starter} not consumable — see {@code CLAUDE.md}). Room-level authorization
 * (EN09.1, see {@link fr.pivot.agilite.poker.ws.PokerChannelInterceptor}) is deliberately built
 * without depending on a trusted client identity at all — access is governed exclusively by an
 * opaque, server-issued room access token (see {@code
 * fr.pivot.agilite.poker.ws.RoomAccessGrantService}), never by a client-supplied tenant/user
 * claim. This record must never be extended with a tenantId/userId field populated from client
 * input — that would silently reintroduce the exact IDOR vector this module's absolute rule
 * forbids for REST endpoints ("tenantId extrait du body / header — jamais").
 *
 * @param connectionId server-generated random correlation id, unique per WebSocket connection
 */
public record WsConnectionPrincipal(String connectionId) implements Principal {

    /**
     * Returns the connection id, used by Spring as the STOMP user-destination name.
     *
     * @return {@link #connectionId()}
     */
    @Override
    public String getName() {
        return connectionId;
    }
}
